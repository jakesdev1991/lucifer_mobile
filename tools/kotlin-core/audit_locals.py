#!/usr/bin/env python3
"""Compile-lite: catch the Kotlin error a reviewer skims past.

There is no JDK in this workspace, so nothing here is compiled. The error class this catches is the one
that survives every other kind of review: **a local used before its own declaration in the same
function.** It reads perfectly — the names are all real, the logic is right — and it does not compile.

It was written after a real instance: an `observations += safe` line ended up above
`val safe = ObservationFence.sanitize(...)` during an edit. The spec, the fuzz harness, the static
claims and a full read of the diff all passed; the compiler would not have.

The check is deliberately conservative, because a linter that cries wolf stops being run:

  * only `val`/`var` declarations inside a function body are considered (class properties are skipped)
  * a name is only examined when it is declared exactly once in the file
  * any file parameter or class-level name with the same spelling disables the check for that name
  * comments and string literals are stripped before searching (their words are not identifiers)

Run:
    python3 tools/kotlin-core/audit_locals.py            # report
    python3 tools/kotlin-core/audit_locals.py --check    # non-zero on findings
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOURCES = [
    os.path.join(ROOT, "composeApp", "src", "androidMain", "kotlin"),
    os.path.join(ROOT, "composeApp", "src", "commonMain", "kotlin"),
]

DECLARATION = re.compile(r"^(\s*)(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=")
FUN = re.compile(r"^(\s*)(?:@\w+\s+)*(?:private |public |internal |protected |override |suspend |inline )*fun\s")
PARAM = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*:")
CLASS_LIKE = re.compile(r"^(\s*)(?:private |internal )?(?:data |sealed |enum |abstract |open )*(?:class|object|interface)\s")


def strip_comments_and_strings(source: str) -> list[str]:
    """Blank out comments and literal contents line by line, keeping line numbers aligned."""
    out: list[str] = []
    in_block = False
    in_raw = False
    for line in source.splitlines():
        buf = []
        i = 0
        n = len(line)
        while i < n:
            if in_block:
                if line.startswith("*/", i):
                    in_block = False
                    i += 2
                else:
                    i += 1
                continue
            if in_raw:
                if line.startswith('"""', i):
                    in_raw = False
                    i += 3
                    buf.append(" ")
                else:
                    i += 1
                continue
            if line.startswith("//", i):
                break
            if line.startswith("/*", i):
                in_block = True
                i += 2
                continue
            if line.startswith('"""', i):
                in_raw = True
                i += 3
                buf.append(" ")
                continue
            char = line[i]
            if char == '"':
                buf.append(" ")
                i += 1
                while i < n:
                    if line[i] == "\\":
                        i += 2
                        continue
                    if line[i] == '"':
                        break
                    i += 1
                i += 1
                continue
            if char == "'":
                buf.append(" ")
                i += 1
                while i < n:
                    if line[i] == "\\":
                        i += 2
                        continue
                    if line[i] == "'":
                        break
                    i += 1
                i += 1
                continue
            buf.append(char)
            i += 1
        out.append("".join(buf))
    return out


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def signature_params(lines: list[str], start: int) -> set[str]:
    """Parameters of a multi-line signature: read to the closing paren at depth zero."""
    collected: list[str] = []
    depth = 0
    for index in range(start, min(start + 40, len(lines))):
        line = lines[index]
        collected.append(line)
        depth += line.count("(") - line.count(")")
        if depth <= 0 and "(" in "".join(collected):
            break
    text = "\n".join(collected)
    # Everything between the first '(' and its match, then split on top-level commas.
    if "(" not in text:
        return set()
    start_at = text.index("(")
    inner = []
    depth = 0
    for char in text[start_at:]:
        if char == "(":
            depth += 1
            continue
        if char == ")":
            depth -= 1
            if depth == 0:
                break
            continue
        if depth == 1:
            inner.append(char)
    params: set[str] = set()
    for piece in "".join(inner).split(","):
        name = piece.strip().split(":")[0].strip()
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            params.add(name)
    return params


def enclosing_block_kind(lines: list[str], index: int) -> str | None:
    """The kind of the *nearest* block a line sits inside: 'fun', 'class' or 'object'.

    Nearest, not "any": a local inside a function inside an object is a local, and treating the object
    above it as the answer would silently disable the whole check for every composable in the file.
    """
    target_indent = indent_of(lines[index])
    for probe in range(index - 1, -1, -1):
        line = lines[probe]
        if not line.strip():
            continue
        indent = indent_of(line)
        if indent >= target_indent:
            continue
        if "{" not in line:
            continue
        if FUN.match(line):
            return "fun"
        if CLASS_LIKE.match(line):
            return "class"
        if re.search(r"\b(companion )?object\b", line):
            return "object"
    return None


def audit_file(path: str) -> list[str]:
    with open(path, encoding="utf-8") as handle:
        lines = strip_comments_and_strings(handle.read())
    findings: list[str] = []

    # Every name that could legitimately come from elsewhere: parameters and class-level declarations.
    external: set[str] = set()
    for index, line in enumerate(lines):
        if FUN.match(line):
            external.update(signature_params(lines, index))
            named = re.search(r"fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.]+\.)?([A-Za-z_][A-Za-z0-9_]*)", line)
            if named:
                # `val read = input.read(…)` inside `fun read(…)` is a legal shadow: any earlier `read`
                # refers to the function. Do not guess; skip the name.
                external.add(named.group(1))
        if CLASS_LIKE.match(line):
            external.update(PARAM.findall(line))
    declarations: dict[str, list[tuple[int, int]]] = {}
    fun_starts: list[tuple[int, int]] = []  # (line index, indent)
    for index, line in enumerate(lines):
        stripped = line.strip()
        if FUN.match(line):
            fun_starts.append((index, indent_of(line)))
        match = DECLARATION.match(line)
        if match:
            declarations.setdefault(match.group(3), []).append((index, indent_of(line)))
    # A name declared at an indent that matches a class-level style is a property, not a local.
    for name in list(declarations):
        declarations[name] = [(i, d) for (i, d) in declarations[name] if d > 0]
        if not declarations[name]:
            del declarations[name]

    for name, places in declarations.items():
        if len(places) != 1 or name in external or len(name) <= 2:
            continue
        # A member of a class or object body is a property, not a local: order does not matter.
        if enclosing_block_kind(lines, places[0][0]) in ("class", "object"):
            continue
        declare_at, _ = places[0]
        # The enclosing function: the closest `fun` above the declaration.
        enclosing = None
        for start, indent in fun_starts:
            if start < declare_at and indent < indent_of(lines[declare_at]):
                enclosing = (start, indent)
        if enclosing is None:
            continue
        start, _ = enclosing
        # A one-line function (expression body) cannot have this problem.
        if start == declare_at:
            continue
        # Skip the rest of the signature: parameters and the return type are not uses of a local.
        while start < declare_at and "{" not in lines[start] and "=" not in lines[start]:
            start += 1
        if start >= declare_at:
            continue
        pattern = re.compile(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"(?![A-Za-z0-9_])")
        region = "\n".join(lines[start:declare_at])
        for match in pattern.finditer(region):
            before = region[:match.start()]
            line_head = before.rsplit("\n", 1)[-1]
            previous = before.rstrip()[-1:] if before.rstrip() else ""
            # A named argument (`center = …`) is not a use of a local.
            if previous in ("(", ","):
                continue
            # A lambda parameter (`{ text -> … }`) is a declaration.
            if "->" in line_head:
                continue
            line_number = region[:match.start()].count("\n") + start + 1
            findings.append(
                f"{os.path.relpath(path, ROOT)}:{line_number}: '{name}' is used before its declaration "
                f"on line {declare_at + 1} — this does not compile"
            )
            break
    return findings


SELFTEST_BUG = """package fixture

object Fixture {
    fun broken(): String {
        observations += safe
        val safe = "x"
        return safe
    }

    companion object {
        val LATER = setOf("a")
    }

    fun fine(parameter: Int): String {
        val total = parameter + 1
        val list = listOf(total)
        list.forEach { item -> println(item) }
        val named = thing(value = total, other = 2)
        return "$total $named"
    }
}
"""

SELFTEST_BUG_WHEN = """package fixture

object Fixture {
    fun broken(): String {
        val first = "a"
        return second + first
        val second = "b"
    }
}
"""


def selftest() -> int:
    """Prove the checker finds a real one, and does not fire on the four shapes it looks like."""
    import tempfile

    failures = 0
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "Fixture.kt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(SELFTEST_BUG)
        found = audit_file(path)
        if not any("'safe'" in line for line in found):
            print("  FAIL  selftest: the injected use-before-declaration was not found", file=sys.stderr)
            failures += 1
        # `fine()` must stay quiet even though it uses lambdas, named arguments and a companion member.
        noisy = [line for line in found if "'item'" in line or "'total'" in line or "'named'" in line
                 or "'parameter'" in line or "'LATER'" in line]
        if noisy:
            print(f"  FAIL  selftest: false positive(s): {noisy}", file=sys.stderr)
            failures += 1
        if len(found) != 1:
            print(f"  FAIL  selftest: expected exactly 1 finding, got {len(found)}: {found}", file=sys.stderr)
            failures += 1

        path2 = os.path.join(directory, "Fixture2.kt")
        with open(path2, "w", encoding="utf-8") as handle:
            handle.write(SELFTEST_BUG_WHEN)
        if not audit_file(path2):
            print("  FAIL  selftest: a use after a return was not found", file=sys.stderr)
            failures += 1
    if failures == 0:
        print("selftest: the checker finds the bug and ignores the four shapes that look like it")
    return failures


def main() -> int:
    findings: list[str] = []
    selftest_failures = selftest()
    files = 0
    for root in SOURCES:
        for directory, _, names in os.walk(root):
            for name in names:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(directory, name)
                files += 1
                findings.extend(audit_file(path))

    for line in findings:
        print(f"  FAIL  {line}", file=sys.stderr)
    print(f"{files} Kotlin file(s) scanned · {len(findings)} use-before-declaration finding(s)")
    if findings or selftest_failures:
        return 1
    print("LOCALS OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
