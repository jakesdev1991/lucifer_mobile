# Lucifer reference skill library

**Schema:** version 1
**Asset:** `composeApp/src/androidMain/assets/skills/skill_library.json`
**Validation:** `python3 scripts/validate_skill_library.py`

The asset contains 100 reference skills in ten topical packs. It is a bounded knowledge/retrieval catalog—not a plugin system, tool registry, permissions list, or authorization grant. Every skill and pack must declare `readiness: "REFERENCE_ONLY"`. The app rejects unknown schema versions, malformed identifiers, duplicates, broken pack references, missing guidance fields, oversized data, and any readiness value that is not exactly `REFERENCE_ONLY`. The asset is capped at 256 KiB by the runtime loader; pack and entry counts, string lengths, keywords, and source references are also bounded. The standalone Python validator checks the checked-in dataset without Java or Android SDK.

## Learned skills are a different thing

This catalog is static: bundled with the APK, read-only, and every entry is `REFERENCE_ONLY`. Lucifer also
accumulates a small, separate list of behaviours learned from its own turns (corrections, repeated
questions, and recovery tactics that worked). Those live in `filesDir/skills/synth_state.json`, are capped,
and are injected as *habits, not permissions* — they cannot add a tool, grant authorisation, or alter
anything in this library. See [SKILL_SYNTHESIS.md](SKILL_SYNTHESIS.md).

## Separation of concerns

- `MainActivity.buildSkillRegistry()` is the operational status catalog. It describes integrations that exist in this build, including their limitations. It is not generated from this reference asset.
- The Skills panel shows the operational registry and searchable reference library under separate headings. Reference cards expose prerequisites, safety boundaries, verification notes, and pack sources; they are labeled **REFERENCE ONLY · NOT RUNNABLE**.
- `SkillLibrary.search()` provides bounded, deterministic lexical retrieval over names, keywords, categories, and summaries. Model prompt context is limited to a small number of relevant entries and a maximum character budget.
- Four Horsemen prompts and workflow planning/reflection receive retrieved material only as advice. Prompts explicitly direct the model to check the active capability registry and honor prerequisites and safety boundaries. Neither retrieval nor generated workflow text can add a tool, bypass command confirmation, mutate Permission Manager X grants, or authorize external/high-impact writes.
- Load/validation failure is fail-closed for the library: no entries are surfaced or injected. It does not disable or misreport the separate operational registry.

## Entry and pack fields

Top-level `schemaVersion`, `library`, `packs`, and `skills` are required. A pack supplies an ID/title, reference-only readiness, prerequisites, safety boundary, verification/evidence guidance, and HTTPS source links. A skill supplies a unique ID, owning pack ID, display name, short summary, explicit reference-only readiness, and search keywords. Pack sources provide starting points for review, not a claim that every statement in that pack has been independently validated or that the source implements Lucifer functionality.

## Safety and scope

The catalog intentionally covers Android/Pixel runtime behavior, Shizuku, networking, Termux/Linux, software delivery, research/evidence, local services, agent workflows, privacy/security, and wallets/payments. In particular:

- Pixel hardware does not establish the installed OS/build; use runtime device evidence.
- Shizuku access is distinct from Lucifer features. Lucifer must not change Permission Manager X permissions or imply that Shizuku authorizes arbitrary operations.
- Network and VPN entries are design/reference material. Lucifer currently reports active VPN status but has no `VpnService`, packet capture, filtering, or forwarding implementation.
- Wallet/payment guidance is not a live receiving or spending capability. Inbound-payment design does not enable outgoing transfers.
- External browser writes, public deployments, and unrestricted background actions remain governed by the separately chosen confirmation boundaries; a skill entry cannot relax them.

## Updating

When changing the schema, update the Kotlin decoder, this document, and the validator together. If adding/removing entries or packs, update the validator's expected totals intentionally. Review source links and capability claims, run the validator and `git diff --check`, then run Android compilation/tests where a JDK and SDK are available. Do not label guidance as operational merely because an assistant can retrieve it.
