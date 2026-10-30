#!/usr/bin/env python3
"""Executable spec for the egress policy and ledger.

`EgressLedger.kt` decides what leaves the device. That Kotlin is compiled by AGP, which is not
available in every workspace, and a privacy rule that cannot be executed cannot be trusted. This file
is the same rules written twice-over in Python so they *can* be exercised anywhere:

    python3 tools/privacy-core/spec_check.py

`EgressCoreTests.kt` in this directory asserts the identical cases against the real Kotlin. When both
run (`scripts/verify_privacy_core.sh`), they must agree. If this file and the Kotlin ever disagree,
the Kotlin is the thing that ships — so a mismatch is a bug in one of them, never a pass.

Exit code is 0 only when every case holds.
"""

from __future__ import annotations

import re
import sys

# ── mirror of EgressPolicy ────────────────────────────────────────────

MAX_ROUTE_CHARS = 48
LOCAL_EXACT = {"localhost", "127.0.0.1", "0.0.0.0", "::1", "10.0.2.2"}
LOCAL_SUFFIXES = (".local", ".localhost", ".internal", ".lan", ".home")
SUFFIX_RULES = (
    ("api.nvidia.com", "MODEL"),
    ("nvidia.com", "MODEL"),
    ("duckduckgo.com", "SEARCH"),
    ("github.com", "CODE"),
    ("githubusercontent.com", "CODE"),
    ("publicnode.com", "RPC"),
    ("arbitrum.io", "RPC"),
    ("llamarpc.com", "RPC"),
    ("ycombinator.com", "PAGE"),
)


def parse(url: str):
    """(host, route) or None. Route never contains a query string or fragment."""
    trimmed = url.strip()
    if not trimmed or len(trimmed) > 4096:
        return None
    scheme_end = trimmed.find("://")
    if scheme_end <= 0:
        return None
    if trimmed[:scheme_end].lower() not in ("http", "https"):
        return None
    rest = trimmed[scheme_end + 3:]
    if not rest:
        return None
    authority_end = len(rest)
    for marker in ("/", "?", "#"):
        at = rest.find(marker)
        if 0 <= at < authority_end:
            authority_end = at
    authority = rest[:authority_end]
    if not authority:
        return None
    at = authority.rfind("@")
    if at >= 0:
        authority = authority[at + 1:]
    if not authority:
        return None
    if authority.startswith("["):
        close = authority.find("]")
        if close < 0:
            return None
        host = authority[: close + 1]
    else:
        colon = authority.find(":")
        host = authority[:colon] if colon >= 0 else authority
    host = host.lower()
    if not plausible_host(host):
        return None
    route = rest[authority_end:]
    for marker in ("?", "#"):
        cut = route.find(marker)
        if cut >= 0:
            route = route[:cut]
    if not route:
        route = "/"
    return host, truncate_route(route)


def plausible_host(host: str) -> bool:
    if not host or len(host) > 253:
        return False
    if host.startswith("["):
        return host.endswith("]") and len(host) > 2
    for ch in host:
        if not (ch.isalnum() or ch in ".-_"):
            return False
    return "." in host or host == "localhost"


def truncate_route(route: str) -> str:
    return route if len(route) <= MAX_ROUTE_CHARS else route[: MAX_ROUTE_CHARS - 1] + "…"


def is_local(host: str) -> bool:
    h = host.lower().strip("[]")
    if h in LOCAL_EXACT or host.lower() in LOCAL_EXACT:
        return True
    if any(h.endswith(s) for s in LOCAL_SUFFIXES):
        return True
    if h == "localhost" or h.startswith("127.") or h.startswith("10."):
        return True
    if h.startswith("192.168.") or h.startswith("169.254."):
        return True
    if h.startswith("172."):
        second = h[4:].split(".")[0]
        if second.isdigit() and 16 <= int(second) <= 31:
            return True
    return False


def classify(host: str) -> str:
    h = host.lower()
    if is_local(h):
        return "LOCAL"
    for suffix, kind in SUFFIX_RULES:
        if h == suffix or h.endswith("." + suffix):
            return kind
    return "PAGE"


def has_query(url: str) -> bool:
    scheme_end = url.find("://")
    return "?" in (url[scheme_end + 3:] if scheme_end >= 0 else url)


def decide(url: str, online: bool, user_initiated: bool, expected: str | None = None):
    """(allowed, kind, outcome, host, reason)"""
    parsed = parse(url)
    if parsed is None:
        return False, "UNKNOWN", "BLOCKED_POLICY", "", "Destination has no identifiable host."
    host, _ = parsed
    if is_local(host):
        return True, "LOCAL", "ALLOWED", host, "Device-local."
    if not online:
        return False, expected or classify(host), "BLOCKED_OFFLINE", host, "Network switch is OFF."
    kind = expected or classify(host)
    reason = "Unrecognised public host." if kind == "PAGE" and expected is None else ""
    return True, kind, "ALLOWED", host, reason


# ── mirror of EgressCodec + EgressLog ─────────────────────────────────

FIELD_COUNT = 7
KIND_ORDER = ["LOCAL", "MODEL", "VISION", "SEARCH", "PAGE", "RPC", "CODE", "UNKNOWN"]


def clean(value: str) -> str:
    return value.replace("\t", " ").replace("\n", " ").replace("\r", " ")[:256]


def encode(rec: dict) -> str:
    return "\t".join(
        [
            str(rec["epoch"]),
            clean(rec["host"]),
            clean(rec["route"]),
            rec["kind"],
            "true" if rec["userInitiated"] else "false",
            rec["outcome"],
            "true" if rec["hadQuery"] else "false",
        ]
    )


def decode(line: str):
    if not line:
        return None
    parts = line.split("\t")
    if len(parts) != FIELD_COUNT:
        return None
    try:
        epoch = int(parts[0])
    except ValueError:
        return None
    kind = parts[3] if parts[3] in KIND_ORDER else "UNKNOWN"
    outcome = parts[5] if parts[5] in ("ALLOWED", "BLOCKED_OFFLINE", "BLOCKED_POLICY") else "BLOCKED_POLICY"
    return {
        "epoch": epoch,
        "host": parts[1],
        "route": parts[2],
        "kind": kind,
        "userInitiated": parts[4] == "true",
        "outcome": outcome,
        "hadQuery": parts[6] == "true",
    }


class Log:
    def __init__(self, capacity: int = 400):
        assert capacity > 0
        self.capacity = capacity
        self.entries: list[dict] = []

    def record(self, rec: dict) -> None:
        self.entries.append(rec)
        while len(self.entries) > self.capacity:
            self.entries.pop(0)

    def blocked(self) -> int:
        return sum(1 for e in self.entries if e["outcome"] != "ALLOWED")

    def counts(self) -> dict:
        return {k: sum(1 for e in self.entries if e["kind"] == k) for k in KIND_ORDER if any(e["kind"] == k for e in self.entries)}

    def summary(self) -> str:
        if not self.entries:
            return "No outbound requests recorded."
        n = len(self.entries)
        parts = [f"{n} attempt" + ("" if n == 1 else "s"), f"{n - self.blocked()} allowed", f"{self.blocked()} blocked"]
        parts += [f"{k} {v}" for k, v in self.counts().items()]
        return " · ".join(parts)

    def render(self, maximum: int = 30) -> str:
        if not self.entries:
            return "No outbound requests recorded."
        tail = self.entries[-maximum:]
        lines = [
            f"{e['kind']} {e['host']}{e['route']}"
            + ("" if e["outcome"] == "ALLOWED" else f" · {label(e['outcome'])}")
            # Who asked matters as much as where it went: an unmarked line is one a person started.
            + ("" if e.get("userInitiated", True) else " · on his own")
            for e in tail
        ]
        return "\n".join(lines) + "\n" + self.summary()


def label(outcome: str) -> str:
    return {"ALLOWED": "ALLOWED", "BLOCKED_OFFLINE": "BLOCKED", "BLOCKED_POLICY": "REFUSED"}[outcome]


# ── the cases ─────────────────────────────────────────────────────────

passed = 0
failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    global passed
    if condition:
        passed += 1
    else:
        failures.append(f"{name}{(': ' + detail) if detail else ''}")


def case_parse() -> None:
    check("parse: normal https", parse("https://integrate.api.nvidia.com/v1/chat/completions") == (
        "integrate.api.nvidia.com", "/v1/chat/completions"))
    check("parse: no path becomes /", parse("https://example.com") == ("example.com", "/"))
    check("parse: port stripped", parse("http://127.0.0.1:8030/health") == ("127.0.0.1", "/health"))
    check("parse: ipv6 literal", parse("https://[::1]:8030/x") == ("[::1]", "/x"))
    check("parse: credentials dropped", parse("https://user:pw@evil.example/x") == ("evil.example", "/x"))
    check("parse: query stripped from route",
          parse("https://html.duckduckgo.com/html/?q=how+to+hide") == ("html.duckduckgo.com", "/html/"))
    check("parse: fragment stripped", parse("https://a.example/p#frag") == ("a.example", "/p"))
    check("parse: no scheme refused", parse("integrate.api.nvidia.com/v1") is None)
    check("parse: ftp refused", parse("ftp://example.com/x") is None)
    check("parse: bare scheme refused", parse("https://") is None)
    check("parse: empty refused", parse("") is None)
    check("parse: single-label host refused", parse("https://intranet/x") is None)
    check("parse: javascript refused", parse("javascript:alert(1)") is None)
    check("parse: long path truncated and marked",
          parse("https://a.example/" + "x" * 200)[1].endswith("…")
          and len(parse("https://a.example/" + "x" * 200)[1]) == MAX_ROUTE_CHARS)


def case_classify() -> None:
    check("class: nvidia is MODEL", classify("integrate.api.nvidia.com") == "MODEL")
    check("class: duckduckgo is SEARCH", classify("html.duckduckgo.com") == "SEARCH")
    check("class: subdomain of duckduckgo is SEARCH", classify("x.duckduckgo.com") == "SEARCH")
    check("class: suffix spoof is PAGE", classify("duckduckgo.com.evil.example") == "PAGE")
    check("class: prefix spoof is PAGE", classify("evil-duckduckgo.com") == "PAGE")
    check("class: github is CODE", classify("raw.githubusercontent.com") == "CODE")
    check("class: rpc node is RPC", classify("ethereum-rpc.publicnode.com") == "RPC")
    check("class: arbitrum is RPC", classify("arb1.arbitrum.io") == "RPC")
    check("class: hacker news is PAGE", classify("news.ycombinator.com") == "PAGE")
    check("class: unknown host is PAGE", classify("unknown.example.org") == "PAGE")
    check("class: loopback is LOCAL", classify("127.0.0.1") == "LOCAL")


def case_local() -> None:
    for host in ["127.0.0.1", "localhost", "10.0.2.2", "10.1.2.3", "172.16.0.1",
                 "172.31.255.255", "192.168.1.20", "169.254.1.1", "::1", "[::1]",
                 "printer.local", "svc.internal"]:
        check(f"local: {host}", is_local(host))
    for host in ["172.32.0.1", "8.8.8.8", "example.com", "1.1.1.1", "192.169.0.1"]:
        check(f"not local: {host}", not is_local(host))


def case_decide() -> None:
    allowed, kind, outcome, _, _ = decide("https://integrate.api.nvidia.com/v1/chat", True, True)
    check("decide: online model call allowed", allowed and kind == "MODEL" and outcome == "ALLOWED")

    blocked = decide("https://integrate.api.nvidia.com/v1/chat", False, True)
    check("decide: offline model call blocked", not blocked[0] and blocked[2] == "BLOCKED_OFFLINE")

    local = decide("http://127.0.0.1:8030/health", False, True)
    check("decide: loopback survives network off", local[0] and local[1] == "LOCAL")

    # Every real destination in this repo is refused when the switch is off.
    for url in ["https://integrate.api.nvidia.com/v1/chat/completions",
                "https://html.duckduckgo.com/html/?q=x",
                "https://github.com/trending?since=daily",
                "https://news.ycombinator.com/",
                "https://ethereum-rpc.publicnode.com",
                "https://api.github.com/git/trees/main?recursive=1"]:
        check(f"dark blocks {url}", not decide(url, False, True)[0])

    check("decide: unparseable refused even when online", not decide("notaurl", True, True)[0])
    check("decide: vision expectation wins", decide("https://integrate.api.nvidia.com/v1", True, True, "VISION")[1] == "VISION")


def case_ledger() -> None:
    log = Log(capacity=3)
    for i in range(5):
        log.record({"epoch": i, "host": "h", "route": "/", "kind": "PAGE",
                    "userInitiated": True, "outcome": "ALLOWED", "hadQuery": False})
    check("log: capacity enforced", len(log.entries) == 3)
    check("log: oldest dropped", [e["epoch"] for e in log.entries] == [2, 3, 4])

    log2 = Log(capacity=10)
    check("log: empty summary", log2.summary() == "No outbound requests recorded.")
    log2.record({"epoch": 1, "host": "a", "route": "/", "kind": "MODEL",
                 "userInitiated": True, "outcome": "ALLOWED", "hadQuery": False})
    log2.record({"epoch": 2, "host": "b", "route": "/", "kind": "SEARCH",
                 "userInitiated": True, "outcome": "BLOCKED_OFFLINE", "hadQuery": True})
    check("log: blocked counted", log2.blocked() == 1)
    check("log: summary shape", log2.summary().startswith("2 attempts · 1 allowed · 1 blocked"))
    check("log: class order preserved", log2.summary().endswith("MODEL 1 · SEARCH 1"))
    own = Log()
    own.record({"epoch": 1, "host": "example.com", "route": "/a", "kind": "PAGE",
                "userInitiated": False, "outcome": "ALLOWED", "hadQuery": False})
    check("log: a self-started fetch is marked", "on his own" in own.render())
    check("log: an owner fetch is not marked", "on his own" not in log2.render())

    # Never a query string, even when the URL had one.
    host, route = parse("https://html.duckduckgo.com/html/?q=my+private+search")[0], parse(
        "https://html.duckduckgo.com/html/?q=my+private+search")[1]
    check("redaction: query absent from route", "?" not in route and "private" not in route)
    check("redaction: query flagged separately", has_query("https://html.duckduckgo.com/html/?q=x"))


def case_codec() -> None:
    rec = {"epoch": 1717000000000, "host": "integrate.api.nvidia.com", "route": "/v1/chat/completions",
           "kind": "MODEL", "userInitiated": True, "outcome": "ALLOWED", "hadQuery": False}
    line = encode(rec)
    check("codec: 7 fields", line.count("\t") == FIELD_COUNT - 1)
    check("codec: roundtrip", decode(line) == rec)
    check("codec: corrupt line dropped", decode("only\tthree\tfields") is None)
    check("codec: non-numeric epoch dropped", decode("x\th\t/\tPAGE\ttrue\tALLOWED\tfalse") is None)
    check("codec: empty dropped", decode("") is None)
    check("codec: unknown enum degrades", decode("1\th\t/\tNUKE\ttrue\tALLOWED\tfalse")["kind"] == "UNKNOWN")
    check("codec: bad outcome degrades",
          decode("1\th\t/\tPAGE\ttrue\tNUKE\tfalse")["outcome"] == "BLOCKED_POLICY")
    check("codec: tab in route cannot split the row",
          decode(encode({"epoch": 1, "host": "h", "route": "/a\tb", "kind": "PAGE",
                         "userInitiated": False, "outcome": "ALLOWED", "hadQuery": False})) is not None)

    # A ledger with one corrupt line still yields the rest.
    lines = [encode(rec), "garbage", encode(rec)]
    parsed = [r for r in (decode(l) for l in lines) if r is not None]
    check("codec: corrupt line does not stop the file", len(parsed) == 2)

    log = Log(capacity=2)
    log.record(rec)
    log.record(rec)
    log.record(rec)
    reloaded = [decode(l) for l in [encode(e) for e in log.entries]]
    check("codec: file roundtrip keeps the ring", len(reloaded) == 2 and all(reloaded))


# ── mirror of PlayClassifier (which Play stack is on the device) ──────

MICROG_SIGNAL_THRESHOLD = 2


def microg_signals(facts: dict) -> list[str]:
    signals = []
    if "microg" in facts.get("gmsLabel", "").lower():
        signals.append("GMS label says microG")
    version = facts.get("gmsVersionName", "")
    if re.match(r"^0\.(2|3)\.[0-9.]+$", version):
        signals.append("GMS version has microG's shape")
    if facts.get("vendingInstalled") and "microg" in facts.get("vendingLabel", "").lower():
        signals.append("Play Store is microG Companion")
    installer = facts.get("gmsInstaller", "")
    if facts.get("gmsInstalled") and installer and not installer.startswith("com.android.vending"):
        signals.append("GMS was not installed by the Play Store")
    return signals


def classify_play(facts: dict) -> str:
    if facts.get("revenancedGmsInstalled"):
        return "REVANCED_MICROG"
    if not facts.get("gmsInstalled"):
        return "MICROG" if facts.get("microgPreviewInstalled") else "ABSENT"
    if facts.get("microgPreviewInstalled"):
        return "MICROG"
    signals = microg_signals(facts)
    if len(signals) >= MICROG_SIGNAL_THRESHOLD:
        return "MICROG"
    if not signals:
        return "GOOGLE"
    return "UNKNOWN"


def push_verdict(flavor: str) -> str:
    return {
        "GOOGLE": "PUSH: Google delivers it — a device token, your IP and your timing",
        "MICROG": "PUSH: still Google — microG checks in with android.clients.google.com and holds a socket to mtalk.google.com",
        "REVANCED_MICROG": "PUSH: still Google — microG checks in with android.clients.google.com and holds a socket to mtalk.google.com",
        "ABSENT": "PUSH: only apps with their own socket (Signal, UnifiedPush) will notify you",
        "UNKNOWN": "PUSH: unknown until the Play stack is identified",
    }[flavor]


def case_play_env() -> None:
    google = {"gmsInstalled": True, "gmsLabel": "Google Play services", "gmsVersionName": "25.08.12",
              "gmsInstaller": "com.android.vending", "vendingInstalled": True,
              "vendingLabel": "Google Play Store"}
    check("play: stock Google detected", classify_play(google) == "GOOGLE")

    microg = {"gmsInstalled": True, "gmsLabel": "microG Services", "gmsVersionName": "0.3.15.250932",
              "gmsInstaller": "", "vendingInstalled": True, "vendingLabel": "microG Companion"}
    check("play: official microG detected", classify_play(microg) == "MICROG")
    check("play: microG still names Google for push", "mtalk.google.com" in push_verdict("MICROG"))
    check("play: google push names Google", "Google" in push_verdict("GOOGLE"))
    check("play: no play services means app sockets only", "Signal" in push_verdict("ABSENT"))

    check("play: ReVanced GmsCore detected",
          classify_play({"revenancedGmsInstalled": True}) == "REVANCED_MICROG")
    check("play: microG preview package detected",
          classify_play({"microgPreviewInstalled": True}) == "MICROG")
    check("play: nothing installed is ABSENT", classify_play({}) == "ABSENT")

    # One signal is not enough: say UNKNOWN and show the evidence.
    weak = {"gmsInstalled": True, "gmsLabel": "Google Play services", "gmsVersionName": "0.3.2",
            "gmsInstaller": "com.android.vending"}
    check("play: a single signal is UNKNOWN, not a guess", classify_play(weak) == "UNKNOWN")
    check("play: the unknown case still lists its evidence", len(microg_signals(weak)) == 1)

    sideloaded = {"gmsInstalled": True, "gmsLabel": "Google Play services",
                  "gmsVersionName": "25.08.12", "gmsInstaller": "com.android.packageinstaller"}
    check("play: sideloaded Google is UNKNOWN, not microG", classify_play(sideloaded) == "UNKNOWN")

    spelled = {"gmsInstalled": True, "gmsLabel": "microG Services Core", "gmsVersionName": "0.2.27.223616"}
    check("play: microG 0.2 preview build string detected", classify_play(spelled) == "MICROG")


def privilege_verdict(facts: dict) -> str:
    flavor = classify_play(facts)
    if flavor == "ABSENT":
        return "PRIVILEGE: no Play Services installed"
    if facts.get("gmsIsSystemApp") and flavor == "GOOGLE":
        return "PRIVILEGE: SYSTEM APP — platform-level reach that cannot be revoked like a normal app"
    if facts.get("gmsIsSystemApp"):
        return "PRIVILEGE: SYSTEM APP — even a microG build flashed into /system keeps that reach"
    return "PRIVILEGE: ordinary app — same sandbox as everything else; permissions and network are yours to revoke"


def case_privilege() -> None:
    sandboxed = {"gmsInstalled": True, "gmsLabel": "Google Play services", "gmsVersionName": "25.08.12",
                 "gmsInstaller": "com.android.vending", "vendingInstalled": True,
                 "vendingLabel": "Google Play Store", "gmsIsSystemApp": False}
    check("privilege: sandboxed Play is revocable", "ordinary app" in privilege_verdict(sandboxed))

    stock = dict(sandboxed, gmsIsSystemApp=True)
    check("privilege: system Play is flagged", "SYSTEM APP" in privilege_verdict(stock))
    check("privilege: system verdict names the risk",
          "cannot be revoked" in privilege_verdict(stock))

    # microG is not automatically less privileged: flashed builds land in /system/priv-app.
    flashed_microg = {"gmsInstalled": True, "gmsLabel": "microG Services",
                      "gmsVersionName": "0.3.15.250932", "gmsIsSystemApp": True}
    check("privilege: system microG is still privileged", "SYSTEM APP" in privilege_verdict(flashed_microg))
    check("privilege: the microG case says so explicitly",
          "/system" in privilege_verdict(flashed_microg))

    user_microg = {"gmsInstalled": True, "gmsLabel": "microG Services",
                   "gmsVersionName": "0.3.15.250932", "gmsIsSystemApp": False}
    check("privilege: user-installed microG is ordinary", "ordinary app" in privilege_verdict(user_microg))

    check("privilege: absent means nothing to revoke",
          "no Play Services" in privilege_verdict({}))


def main() -> int:
    case_parse()
    case_classify()
    case_local()
    case_decide()
    case_ledger()
    case_codec()
    case_play_env()
    case_privilege()

    for line in failures:
        print(f"FAIL  {line}")
    print()
    if failures:
        print(f"FAILED · {passed} passed, {len(failures)} failed")
        return 1
    print(f"PASS · {passed} checks (egress policy + ledger spec)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
