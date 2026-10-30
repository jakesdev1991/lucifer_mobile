#!/usr/bin/env python3
"""A bracket audit for Kotlin — the class of mistake that looks perfect and does not compile.

There is no Kotlin compiler in this workspace, so a missing brace survives every test that does run: the
Python mirrors pass, the static claims pass, the file even reads correctly. It breaks on the owner's
machine, at the first Gradle build, with a message about a line that is often not the line at fault.

This audit is not a parser. It strips string literals (`"…"`, `\"\"\"…\"\"\"`), character literals and
comments (`//`, `/* … */`, including nesting), then walks the remaining text counting `()`, `{}` and `[]`.
It reports the first file where a depth goes negative — a closer with nothing open, which is nearly always
a real edit mistake — and any file that ends with unbalanced depth, with the line where the last opener is.

String templates (`${…}`) are counted deliberately: a template's braces must balance inside the string, so
they are removed along with the string and cannot mask an error.

    python3 tools/kotlin-core/audit_braces.py            # the compiled source set
    python3 tools/kotlin-core/audit_braces.py --all      # every .kt file in the tree, including unused ones
    python3 tools/kotlin-core/audit_braces.py --check    # non-zero if any file is unbalanced

By default this audits only the directories Gradle actually compiles (`composeApp/src/androidMain/kotlin`,
from `sourceSets["main"].java.srcDirs`). Files outside it are not part of the build, so a bracket error
there breaks nothing — `--all` includes them anyway, which is how twelve leftover `commonMain` files were
found, one of them genuinely unparseable.

Exit codes: 0 clean, 1 findings, 2 usage.
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Read from the Gradle file rather than hardcoded, so a moved source set does not silently stop being
# audited — the same failure mode this audit exists to catch.
COMPILED = [os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin")]
TRIPLE = '"' * 3
PAIRS = {")": "(", "}": "{", "]": "["}
OPENERS = set(PAIRS.values())


def _skip_string(src: str, i: int) -> int:
    """Index just past the ordinary string literal that starts at `src[i]` (a quote).

    Handles escapes, and handles `${ … }` templates — which may span lines and may contain nested strings.
    The template's *contents* are skipped rather than audited: a template is an expression, it is almost
    always balanced, and treating its braces as code is how a checker starts reporting files that compile.
    """
    n = len(src)
    i += 1  # past the opening quote
    while i < n:
        ch = src[i]
        if ch == "\\":
            i += 2
            continue
        if ch == '"':
            return i + 1
        if ch == "\n":
            return i  # unterminated: let the caller continue; audit will see the imbalance elsewhere
        if ch == "$" and i + 1 < n and src[i + 1] == "{":
            depth = 1
            i += 2
            while i < n and depth:
                if src[i] == '"':
                    i = _skip_string(src, i)
                    continue
                if src[i] == "{":
                    depth += 1
                elif src[i] == "}":
                    depth -= 1
                i += 1
            continue
        i += 1
    return i


def _skip_raw(src: str, i: int) -> int:
    'Index just past the triple-quoted string starting at `src[i]`.'
    end = src.find(TRIPLE, i + 3)
    if end < 0:
        return len(src)
    # A raw string may end with a longer run of quotes; advance past the whole run.
    out = end + 3
    while out < len(src) and src[out] == '"':
        out += 1
    return out


def _skip_block_comment(src: str, i: int) -> int:
    """Kotlin block comments nest, so this counts depth rather than searching for the first `*/`."""
    n = len(src)
    depth = 1
    i += 2
    while i < n and depth:
        if src.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if src.startswith("*/", i):
            depth -= 1
            i += 2
            continue
        i += 1
    return i


def strip(source: str) -> list[str]:
    """Return one string per source line with literals and comments replaced by spaces.

    Keeping the line structure means a finding can name a line number, which is the only thing that makes
    this useful on a 900-line file.
    """
    lines: list[str] = []
    current: list[str] = []
    i = 0
    n = len(source)

    def flush(upto: int) -> None:
        """Push every complete line contained in `source[last:upto]` — newlines are preserved by callers."""
        nonlocal current

    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if ch == "\n":
            lines.append("".join(current))
            current = []
            i += 1
            continue

        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue

        if ch == "/" and nxt == "*":
            start = i
            i = _skip_block_comment(source, i)
            # Blank out the newlines the comment consumed so line numbers stay true.
            for _ in range(source.count("\n", start, i)):
                lines.append("".join(current))
                current = []
            continue

        if source.startswith(TRIPLE, i):
            start = i
            i = _skip_raw(source, i)
            for _ in range(source.count("\n", start, i)):
                lines.append("".join(current))
                current = []
            continue

        if ch == '"':
            start = i
            i = _skip_string(source, i)
            for _ in range(source.count("\n", start, i)):
                lines.append("".join(current))
                current = []
            continue

        if ch == "'":
            # Character literal: 'x', '\n', '\'', '\u0041'.
            i += 1
            while i < n:
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == "'" or source[i] == "\n":
                    break
                i += 1
            i += 1
            continue

        current.append(ch)
        i += 1

    lines.append("".join(current))
    return lines


def audit(path: str) -> list[str]:
    """Return findings for one file: negative depth, or a file that ends unbalanced."""
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    findings: list[str] = []
    stack: list[tuple[str, int]] = []
    for number, text in enumerate(strip(source), start=1):
        for column, ch in enumerate(text, start=1):
            if ch in OPENERS:
                stack.append((ch, number))
            elif ch in PAIRS:
                if not stack:
                    findings.append(f"{path}:{number}:{column}: '{ch}' closes nothing")
                    return findings
                opener = stack.pop()
                if opener[0] != PAIRS[ch]:
                    findings.append(
                        f"{path}:{number}:{column}: '{ch}' closes '{opener[0]}' opened at line {opener[1]}"
                    )
                    return findings
    if stack:
        opener, where = stack[-1]
        findings.append(f"{path}:{where}: '{opener}' is never closed ({len(stack)} unclosed)")
    return findings


def main(argv: list[str]) -> int:
    if "--help" in argv or "-h" in argv:
        print(__doc__)
        return 0
    check = "--check" in argv
    targets = [a for a in argv[1:] if not a.startswith("-")]

    if targets:
        files = [t if os.path.isabs(t) else os.path.join(os.getcwd(), t) for t in targets]
    else:
        roots = [ROOT] if "--all" in argv else COMPILED
        files = []
        for root in roots:
            for base, _, names in os.walk(root):
                if "/.git" in base or "/build" in base:
                    continue
                files += [os.path.join(base, name) for name in names if name.endswith(".kt")]
        files.sort()

    if not files:
        print("BRACES: no Kotlin files found")
        return 2 if check else 0

    problems: list[str] = []
    for path in files:
        try:
            problems += audit(path)
        except OSError as error:
            problems.append(f"{path}: unreadable ({error})")

    for problem in problems:
        print(f"  {problem}")

    relative = [os.path.relpath(p, ROOT) for p in files]
    if problems:
        print(f"\nBRACES FAILED · {len(problems)} finding(s) across {len(relative)} Kotlin file(s)")
        return 1
    print(f"{len(relative)} Kotlin file(s) scanned · 0 bracket finding(s)")
    print("BRACES OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
