#!/usr/bin/env python3
"""Validate Lucifer's reference-only skill catalog without Android/Gradle dependencies."""

import json
import re
import sys
from pathlib import Path

ASSET = Path(__file__).resolve().parents[1] / "composeApp/src/androidMain/assets/skills/skill_library.json"
ID = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
EXPECTED_SCHEMA = 1
EXPECTED_SKILLS = 100
EXPECTED_PACKS = 10


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def bounded_text(value, label: str, maximum: int) -> None:
    require(isinstance(value, str) and 0 < len(value.strip()) <= maximum, f"invalid {label}")


def main() -> int:
    try:
        raw = ASSET.read_bytes()
        require(len(raw) <= 256 * 1024, "asset exceeds Android loader size limit (256 KiB)")
        document = json.loads(raw)
        require(document.get("schemaVersion") == EXPECTED_SCHEMA, "unsupported schemaVersion")
        bounded_text(document.get("library"), "library title", 100)

        packs = document.get("packs")
        skills = document.get("skills")
        require(isinstance(packs, list) and len(packs) == EXPECTED_PACKS, f"expected {EXPECTED_PACKS} packs")
        require(isinstance(skills, list) and len(skills) == EXPECTED_SKILLS, f"expected {EXPECTED_SKILLS} skills")

        pack_ids = set()
        for pack in packs:
            require(isinstance(pack, dict), "pack must be an object")
            pack_id = pack.get("id")
            require(isinstance(pack_id, str) and ID.fullmatch(pack_id), "invalid pack id")
            require(pack_id not in pack_ids, f"duplicate pack id: {pack_id}")
            pack_ids.add(pack_id)
            bounded_text(pack.get("title"), f"{pack_id} title", 80)
            require(pack.get("readiness") == "REFERENCE_ONLY", f"{pack_id} is not reference-only")
            for key, maximum in (("prerequisites", 600), ("safetyBoundary", 600), ("verification", 600)):
                bounded_text(pack.get(key), f"{pack_id} {key}", maximum)
            references = pack.get("references")
            require(isinstance(references, list) and 1 <= len(references) <= 8, f"{pack_id} needs source notes")
            for reference in references:
                bounded_text(reference, f"{pack_id} reference", 240)
                require(reference.startswith("https://"), f"non-HTTPS source reference in {pack_id}")

        skill_ids = set()
        for skill in skills:
            require(isinstance(skill, dict), "skill must be an object")
            skill_id = skill.get("id")
            require(isinstance(skill_id, str) and ID.fullmatch(skill_id), "invalid skill id")
            require(skill_id not in skill_ids, f"duplicate skill id: {skill_id}")
            skill_ids.add(skill_id)
            require(skill.get("packId") in pack_ids, f"{skill_id} references an unknown pack")
            require(skill.get("readiness") == "REFERENCE_ONLY", f"{skill_id} is not explicitly reference-only")
            bounded_text(skill.get("name"), f"{skill_id} name", 100)
            bounded_text(skill.get("summary"), f"{skill_id} summary", 360)
            keywords = skill.get("keywords")
            require(isinstance(keywords, list) and 1 <= len(keywords) <= 16, f"invalid keywords for {skill_id}")
            for keyword in keywords:
                bounded_text(keyword, f"{skill_id} keyword", 48)

        print(f"Valid skill library: schema v{EXPECTED_SCHEMA}, {len(skills)} reference-only skills, {len(packs)} source-documented packs, {len(raw):,} bytes")
        print("No entry is executable or grants authorization; live capability status remains in the app registry.")
        return 0
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError) as error:
        print(f"Skill library validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
