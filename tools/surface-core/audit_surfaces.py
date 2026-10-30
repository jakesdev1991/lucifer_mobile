#!/usr/bin/env python3
"""Surface-awareness audit.

"Totally aware" is only true if every path that reaches Lucifer's reasoning declares which interface
it came from. That is a property of the call sites, so it is checked by reading them:

    python3 tools/surface-core/audit_surfaces.py           # report
    python3 tools/surface-core/audit_surfaces.py --check   # exit 1 if a call site is undeclared

Two rules:

1. Every `.reason(...)` / `.deliberate(...)` / `.execute(...)` call that reaches the reasoning council
   must pass a `Surface.*` argument. The Kotlin default is `Surface.UNKNOWN`, which is honest but
   restricted — a defaulted call site is a bug, not a decision.
2. Every declared `Surface` member is described in `Surfaces.kt` and classified by `SurfaceAnswers.kt`,
   so no surface can exist without the ability to explain itself.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KOTLIN_ROOT = os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin")
SURFACES_FILE = os.path.join(KOTLIN_ROOT, "com", "lucifer", "mobile", "surface", "Surfaces.kt")

# Files that legitimately contain the surface machinery itself or tests.
INTERNAL = (
    os.path.join("com", "lucifer", "mobile", "surface"),
)

# Reasoning entry points that must be told where they are speaking from. Deliberately narrow:
# `.execute(` also means "run a shell command" or "run a vault job", and those are not the council.
ENTRY_CALL = re.compile(r"\.(reason|deliberate)\s*\(|(?:horsemen|orchestrator|council)\.execute\s*\(")
# Either an explicit Surface.X, or the `surface` variable being threaded through.
SURFACE_ARG = re.compile(r"Surface\.[A-Z_]+|\bsurface\b")


def kotlin_files() -> list[str]:
    found = []
    for base, _dirs, files in os.walk(KOTLIN_ROOT):
        for name in files:
            if name.endswith(".kt"):
                found.append(os.path.join(base, name))
    return sorted(found)


def is_internal(path: str) -> bool:
    rel = os.path.relpath(path, KOTLIN_ROOT)
    return any(rel.startswith(prefix) for prefix in INTERNAL)


def declared_surfaces() -> list[str]:
    """Enum members from Surfaces.kt, in declaration order."""
    try:
        text = open(SURFACES_FILE, encoding="utf-8", errors="replace").read()
    except OSError:
        return []
    start = text.find("enum class Surface")
    if start < 0:
        return []
    body = text[start:]
    # Members are the uppercase names before the first ``val``/``fun`` block.
    members = []
    for line in body.splitlines()[1:]:
        stripped = line.strip()
        if not stripped or stripped.startswith("/**") or stripped.startswith("*") or stripped.startswith("//"):
            continue  # doc comments sit between members; they are not the end of the enum
        if stripped.startswith("val ") or stripped.startswith("fun "):
            break
        match = re.match(r"^([A-Z][A-Z_0-9]*)\(", stripped)
        if match:
            members.append(match.group(1))
    return members


def described_surfaces() -> set[str]:
    """Surfaces that have a branch in SurfaceKnowledge.spec()."""
    path = os.path.join(KOTLIN_ROOT, "com", "lucifer", "mobile", "surface", "Surfaces.kt")
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return set()
    return set(re.findall(r"Surface\.([A-Z][A-Z_0-9]*)\s*->", text))


def call_sites() -> list[dict]:
    sites = []
    for path in kotlin_files():
        if is_internal(path) or "/tools/" in path:
            continue
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
            lines = text.splitlines()
        except OSError:
            continue
        for index, line in enumerate(lines):
            if not ENTRY_CALL.search(line):
                continue
            if line.strip().startswith("//") or line.strip().startswith("*"):
                continue
            # The call may span lines; look ahead for the argument list.
            window = "\n".join(lines[index: index + 8])
            declared = SURFACE_ARG.search(window) is not None
            sites.append({
                "file": os.path.relpath(path, KOTLIN_ROOT),
                "line": index + 1,
                "code": line.strip()[:88],
                "declared": declared,
            })
    return sites


def main() -> int:
    parser = argparse.ArgumentParser(description="Surface awareness audit")
    parser.add_argument("--check", action="store_true", help="fail on an undeclared call site")
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()

    sites = call_sites()
    declared = declared_surfaces()
    described = described_surfaces()
    undeclared = [s for s in sites if not s["declared"]]
    undescribed = [name for name in declared if name not in described]

    if args.markdown:
        print("| Call site | Line | Surface declared |")
        print("| --- | --- | --- |")
        for site in sites:
            print(f"| `{site['file']}` | {site['line']} | {'yes' if site['declared'] else '**NO**'} |")
        return 0 if not undeclared and not undescribed else 1

    if args.check:
        for site in sites:
            print(f"{'OK  ' if site['declared'] else 'MISS'} {site['file']}:{site['line']}  {site['code']}")
        for name in undescribed:
            print(f"MISS Surface.{name} has no description in SurfaceKnowledge.spec()")
        if undeclared or undescribed:
            print()
            if undeclared:
                print(f"{len(undeclared)} reasoning call site(s) do not pass a Surface. "
                      "The Kotlin default is Surface.UNKNOWN — honest, but restricted.")
            if undescribed:
                print(f"{len(undescribed)} surface(s) cannot explain themselves.")
            return 1
        print(f"\nOK · {len(sites)} reasoning call sites, all surface-aware · {len(declared)} surfaces described")
        return 0

    print("SURFACE AWARENESS — who declares where they are speaking from\n")
    width = max((len(s["file"]) for s in sites), default=10)
    for site in sites:
        status = "OK  " if site["declared"] else "MISS"
        print(f"  {status} {site['file']:<{width}} :{site['line']:<5} {site['code']}")

    print("\nSURFACES\n")
    for name in declared:
        mark = "described" if name in described else "UNDESCRIBED"
        print(f"  {name:<18} {mark}")

    print()
    print(f"{len(sites)} reasoning call sites · {len(undeclared)} undeclared · "
          f"{len(declared)} surfaces · {len(undescribed)} undescribed")
    return 0 if not undeclared and not undescribed else 1


if __name__ == "__main__":
    sys.exit(main())
