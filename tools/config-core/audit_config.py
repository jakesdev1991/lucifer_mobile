#!/usr/bin/env python3
"""Static check that `files/config/*` has exactly one spelling and one writer.

`config/AppConfig.kt` exists because eleven call sites used to hand-roll the same paths: four
spellings of `vm_host.txt`, four of the memory-MCP token, three of the SSH password, most written with
a raw `FileOutputStream` that would leave a half-written value behind if the process died mid-write.

That refactor is only worth anything if it stays done. This audit fails when:

  * a Kotlin file other than `AppConfig.kt` contains a `"config/..."` literal, or
  * a Kotlin file that touches config paths also writes files directly (`FileOutputStream`,
    `.writeText(`, `.appendText(`), which would bypass the atomic write.

    python3 tools/config-core/audit_config.py --check

Exit code 0 only when both rules hold.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
KOTLIN_ROOT = os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin")
OWNER = os.path.join("config", "AppConfig.kt")

CONFIG_LITERAL = re.compile(r'"config/[A-Za-z0-9_.\-]+"')
RAW_WRITERS = ("FileOutputStream", ".writeText(", ".appendText(", "AtomicFile(")


def kotlin_files() -> list[str]:
    found = []
    for base, _dirs, files in os.walk(KOTLIN_ROOT):
        for name in files:
            if name.endswith(".kt"):
                found.append(os.path.join(base, name))
    return sorted(found)


def scan() -> tuple[list[dict], list[dict]]:
    literals, writers = [], []
    for path in kotlin_files():
        if path.endswith(OWNER):
            continue
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        code = [
            (index + 1, line)
            for index, line in enumerate(lines)
            if not line.lstrip().startswith(("*", "//", "/*"))
        ]
        touches_config = any(CONFIG_LITERAL.search(line) for _n, line in code)
        for number, line in code:
            if CONFIG_LITERAL.search(line):
                literals.append({"file": os.path.relpath(path, ROOT), "line": number, "code": line.strip()[:96]})
            if touches_config and any(marker in line for marker in RAW_WRITERS):
                writers.append({"file": os.path.relpath(path, ROOT), "line": number, "code": line.strip()[:96]})
    return literals, writers


def owner_uses_atomic_write() -> bool:
    path = os.path.join(KOTLIN_ROOT, "com", "lucifer", "mobile", OWNER)
    try:
        body = open(path, encoding="utf-8").read()
    except OSError:
        return False
    return "AtomicFile(" in body and ".writeText(" not in body


def main() -> int:
    parser = argparse.ArgumentParser(description="config path + atomic write audit")
    parser.add_argument("--check", action="store_true", help="fail on a stray config literal or a raw write")
    _args = parser.parse_args()

    literals, writers = scan()
    atomic = owner_uses_atomic_write()

    for item in literals:
        print(f"STRAY-PATH     {item['file']}:{item['line']}  {item['code']}")
    for item in writers:
        print(f"RAW-WRITE      {item['file']}:{item['line']}  {item['code']}")
    if not atomic:
        print(f"NOT-ATOMIC     {OWNER} does not use AtomicFile for its writes")

    problems = len(literals) + len(writers) + (0 if atomic else 1)
    if problems:
        print()
        print(f"{problems} problem(s): config paths belong in AppConfig.kt, and writes there must be atomic.")
        return 1

    print()
    print("OK · every config path named once, every write atomic, no bypass writers")
    return 0


if __name__ == "__main__":
    sys.exit(main())
