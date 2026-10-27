# Lucifer Mobile — adversarial application review

**Review date:** 2026-10-26
**Review type:** source-based security, privacy, safety, correctness, availability, UX, Android-platform, dependency/build/release, and dormant-code review. This is not a penetration test, build certification, or device validation.

## Executive assessment

The active app has materially better boundaries than its aspirational/legacy architecture suggests: typed requests are routed before action, non-read-only shell commands show a confirmation step, Shizuku remains user-started/user-granted, the browser UI is read-only, SSH has strict host-key checking, wallet transaction signing is not exposed, and the opt-in heartbeat only produces private drafts. The app does **not** currently implement public deployment, interactive browser writes, live checkout, or autonomous wallet spending.

The highest-risk mismatch is between what a user could reasonably believe and what the implementation guarantees. Generated EVM wallets are not recoverable; chat and heartbeat data go to NVIDIA without an in-app local-only/data-budget control; the recurring heartbeat has no storage or spend ceiling; and the ToE layer can present generic model output as corpus-grounded when the retrieved evidence was never included in the model request. Separately, several legacy Kai, APPA, evidence-ledger, and “cognitive” modules make security/cryptographic claims that their code does not meet. Those modules are mostly dormant, but they must not be connected to a tool runner or used as policy enforcement as-is.

**Bottom line:** do not deposit funds into app-generated wallets; do not treat APPA/Evidence Ledger/RCOD/GEPA as security controls; do not use the legacy Kai scripts with credentials or valuable browser sessions; and do not treat any build/runtime behavior as verified until the release gate and device matrix below pass. The current source-only review is broad across major active and dormant paths, but it is not a line-by-line audit of every UI/theme/corpus asset.

## Review trace and scope

The review traced: (1) launcher/chat text through `NaturalLanguageRouter`, (2) each terminal destination and its confirmation boundary, (3) the heartbeat service lifecycle and model/file/camera flow, (4) wallet and browser operations, (5) credentials and Android component/permission configuration, (6) knowledge sync and LLM egress, and (7) dormant bridge, native, Python, policy, evidence, and cognitive code. It also checked dependency/build configuration, repository test/CI presence, and declared versus implemented Android capabilities.

Reviewed Android scope includes the Gradle-selected `composeApp/src/androidMain/kotlin` tree (61 Kotlin files, about 12.6K lines after adding the verifier path), manifest/resources, app documentation, native CMake/C++, and `scripts/kai_sandbox`. Per the user's correction, the intended scientific/evidence source is the public repository [`jakesdev1991/Omega_Theory_Everything`](https://github.com/jakesdev1991/Omega_Theory_Everything); the supplied `akesdev1991` spelling does not resolve, so I used the canonical owner already configured in the Android code. I inspected the repository at `main` commit `4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80` and its proof-status documentation/CI records. I did not independently validate the physical interpretations or re-run Lean locally. No Android code/device execution was used to confirm app behavior.

### Omega Theory repository evidence and app-source alignment

The repository is the intended evidence source, and it contains both formal work and explicit limits on what that work establishes:

- [`lean_proofs/README.md`](https://github.com/jakesdev1991/Omega_Theory_Everything/blob/4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80/lean_proofs/README.md) says the Lean directory is **not** a complete formal verification or physical derivation; a kernel-checked statement can still be vacuous in its chosen model. It records a green Lean build and 260 selected kernel-axiom targets on main as of its stated validation date.
- [`lean_proofs/PROOF_AUDIT.md`](https://github.com/jakesdev1991/Omega_Theory_Everything/blob/4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80/lean_proofs/PROOF_AUDIT.md) explicitly distinguishes a correct proof of a weak model from proof of an intended physical claim, and records remaining semantic/non-vacuity and physical-applicability limitations.
- [`lean_proofs/PROOF_MAP.md`](https://github.com/jakesdev1991/Omega_Theory_Everything/blob/4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80/lean_proofs/PROOF_MAP.md) maps claims to exact Lean declarations and statuses; its own labels distinguish conditional/model-internal results from physical evidence.
- [`OmegaUnifiedFoundation.lean`](https://github.com/jakesdev1991/Omega_Theory_Everything/blob/4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80/lean_proofs/OmegaUnifiedFoundation.lean) documents the concrete model's limitations, including its tracial/singleton-style construction, rather than presenting the formalization as a general physical derivation.

I queried GitHub's run metadata: Lean CI succeeded on that exact `main` SHA (run [39636527243](https://github.com/jakesdev1991/Omega_Theory_Everything/actions/runs/39636527243), 2026-10-21). The full repository CI on the same SHA most recently failed (run [40171909239](https://github.com/jakesdev1991/Omega_Theory_Everything/actions/runs/40171909239), 2026-10-25), including dependency-advisory, secret-scan, and MyPy steps. That full-CI result does not negate the Lean workflow result, but it does mean the whole repository should not be described as currently all-green. I did not inspect the secret-scan log or expose any finding values.

I compared the Android's 125 bundled `assets/toe` files against same-named candidates in that repository checkout: **2 identical, 120 different, and 3 with no counterpart**. Thus the in-app corpus is not a byte-current mirror of that `main` snapshot. Also, `EmbeddedToE.syncFromGithub()` flattens paths and constructs `.txt` download URLs under `lean_proofs/` even for mirrors sourced from `txt_proofs/`; current sync logic therefore needs correction and provenance/versioning before the UI can claim it follows the repo's current evidence. The exact source excerpts retrieved by `ToEReasoningEngine` are still not included in its model request (finding A-04 below).

### Lean 4 verifier: repository component versus mobile-app gap

Yes: the Omega Theory repository includes a real Lean 4 proof-checking pipeline. [`lean_proofs/lean-toolchain`](https://github.com/jakesdev1991/Omega_Theory_Everything/blob/4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80/lean_proofs/lean-toolchain) pins Lean; [`lean-ci.yml`](https://github.com/jakesdev1991/Omega_Theory_Everything/blob/4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80/.github/workflows/lean-ci.yml) installs the toolchain/Mathlib cache, runs `lake build ToE`, runs `audit_kernel.py`, and gates statement/cited-name audits. The Lean build checks the declarations in Lean's kernel; `audit_kernel.py` checks selected declarations' transitive axiom dependencies. Separate Python checks (`audit_axioms.py`, `audit_vacuity.py`, `check_statement_audit.py`) provide source-level ratchets, not a replacement Lean kernel. The exact repository `main` commit reviewed above has a successful Lean CI run.

The source audit initially found no Lean executable/toolchain, `lake build` invocation, proof-term verification, or verified CI-attestation ingestion in the app. A source filename/theorem name in the bundled corpus is not itself proof that the exact bundled file was checked by Lean. Nor would a successful Lean check prove a physical interpretation; the repository explicitly scopes that limitation.

The user selected **on-device execution in the Debian Linux VM through Lucifer's existing strict SSH bridge**. An implementation is now present in `Lean4VerificationRunner.kt` and the Settings panel, but is **not yet build- or device-validated**. It is deliberately two-phase: Prepare fetches the public `main` checkout in a detached, dedicated tmux session without running repository audit scripts, then surfaces the exact SHA and `lean-toolchain`; a separate explicit confirmation launches the audits/build for that exact prepared SHA and toolchain. The remote job persists phase/status/logs in a dedicated guest directory and exposes refresh/stop controls. Preparation is capped at 10 minutes and verification at two hours; timeouts are explicit non-PASS states. It refuses non-loopback VM hosts, relies on the bridge's strict known-host pin, rejects a dirty checkout, and checks the canonical origin and confirmed commit/toolchain again before execution. The script mirrors the repository's Python source audits, runs `lake build ToE`, cited-name/statement checks, and `audit_kernel.py`, and reports PASS only if all gates exit successfully. Missing tools, mismatched pins, audit/build failures, and interrupted jobs are surfaced as non-PASS states; Lucifer does not silently install packages or run model-generated Lean.

This is a local verification control/evidence display, not an attestation service and not yet wired into model responses or proof claims. The verified source is the selected repository checkout, not the app's non-identical bundled corpus. Dependency fetches may require guest network access and substantial VM storage/time; stop is best-effort. No Gradle build, Kotlin compile, VM SSH session, or local Lean execution was available here, so the implementation must not be represented as working until exercised on the selected device. Even a successful run establishes formal/source checks for one SHA/toolchain only—not physical validity or a successful Lucifer release.

### Severity key

- **P0 — release blocker:** unacceptable risk for any production/release use until fixed or explicitly gated.
- **P1 — high:** realistic potential for significant loss, privacy breach, unauthorized effect, or materially misleading output.
- **P2 — moderate:** meaningful reliability/UX/security weakness, or a dangerous latent module that is not currently reachable from the normal UI.
- **P3 — low:** cleanup or narrow edge case.

Reachability is stated separately. A dormant defect is not described as an active exploit.

## Findings

### A-01 — Generated wallets have no recovery path; key loss can be permanent

**Severity:** P1 · **Reachability:** active wallet-generation UI; no transaction-signing route is currently exposed.

**Evidence:** `LuciferWallet.generateWallet()` persists key material via `SecretStore` and stores address/chain metadata under app-private files (`data/wallet/LuciferWallet.kt`, approximately lines 60–73, 260+). The wallet UI and existing capability audit disclose that there is no recovery phrase/export. `allowBackup=false` and Android Keystore protection are useful confidentiality controls, but make neither recovery nor migration possible; app data loss, reinstall, device loss, or unrecoverable Keystore invalidation can permanently strand funds. The requested separate Arbitrum USDC receiver is not yet an implemented payment flow.

**Scenario:** a user copies the address to accept a small USDC test payment, later replaces/resets the phone, and cannot retrieve the private key from the old app install. There is no seed phrase, verified export, external signer, or tested restore procedure.

**Counterargument/control:** current UI warns users not to fund wallets; app does not broadcast transactions, and the raw-hash signing path is disabled. This sharply reduces current exposure but does not protect a user who transfers tokens to the displayed address.

**Recommendation/tests:** keep an unmistakable “not recoverable—do not fund” state beside every address. Before any receiving milestone, design and independently test a recovery/restore scheme, key backup/export authentication, chain/account identity, and lost-device recovery. Test fresh install, upgrade, app-data clear, OS restore, Keystore invalidation, and recovery on a second device. Continue to keep outgoing signing disabled until it has a typed transaction preview and separately enforced limits.

### A-02 — Chat and heartbeat disclose content to a third-party model; recurring cost/data scope is easy to underestimate

**Severity:** P1 · **Reachability:** active whenever a configured NVIDIA key is used; heartbeat is active only after explicit opt-in.

**Evidence:** free-form chat is routed through `NemotronClient.chat`; ordinary conversation then invokes `FourHorsemenOrchestrator.deliberate`, which sends the current prompt in three sequential calls (CONQUEST, WAR, FAMINE). The router also forwards up to six prior chat messages (up to 700 characters each) to NVIDIA to classify intent (`NaturalLanguageRouter.kt` and `FourHorsemenOrchestrator.kt`). The heartbeat sends its prompt, public-page extracts, recent session summaries/draft excerpts, and—when separately enabled—a camera still to the NVIDIA vision endpoint (`HeartbeatService.kt` lines 30–78). Settings explain that the camera image is sent and that API use may cost money; there is no local-only mode, per-request egress confirmation, daily usage cap, or sensitive-content redaction.

**Scenario:** a private conversation or terminal excerpt appears in chat history, or a prior heartbeat draft contains personal/business information. The user types an ordinary question or leaves the heartbeat enabled; that content is transmitted to the configured provider. A recurring camera heartbeat can make up to roughly 48 model requests/day if sessions complete on schedule; actual OS timing varies.

**Counterargument/control:** a user must supply the NVIDIA key; camera use is a separate runtime permission and app toggle, is visible through Android's camera indicator, and the image is not intentionally written to disk. Heartbeat prompts prohibit identification and external actions. These are useful controls, but they do not amount to data minimization, a no-cloud setting, or cost control.

**Recommendation/tests:** explain, before first use, which fields/history/image leave the device and the provider destination; offer local-only/disable-cloud and per-feature egress controls; limit history and strip terminal output/secrets by default; add usage/session budgets and a clear Stop/disable path. Test request payloads with canary secrets and verify no unrelated history, keys, or local identifiers are serialized. Verify Stop cancels in-flight requests and the notification accurately reflects camera/network state.

### A-03 — Heartbeat artifact and session history grow without a retention/size limit

**Severity:** P1 · **Reachability:** active after heartbeat opt-in.

**Evidence:** each successful session writes a timestamped Markdown file and appends `sessions.jsonl`; `latest.json` is overwritten, but there is no pruning, quota, maximum artifact count, or delete/retention UI (`HeartbeatService.kt` around lines 86–125 and 140–165). The service continues its loop after recoverable errors and schedules another attempt after the interval (`LuciferHeartbeatService.kt` around lines 78–97).

**Scenario:** a user enables heartbeat and forgets it is running. Dozens of session attempts per day continue for months. Artifacts and logs consume app-private storage; model usage may continue, and a full filesystem can break unrelated app writes or updates. Past drafts also remain locally available indefinitely.

**Counterargument/control:** service requires opt-in, displays a persistent Stop notification, writes only inside private app storage, and can be stopped. The OS may defer/kill it. Those controls do not cap accumulated data or provider usage while it remains enabled.

**Recommendation/tests:** add bounded retention (count/bytes/age), an explicit “delete all heartbeat data” action, a maximum session cadence and daily request/token ceiling, backoff after errors, and a low-storage stop condition. Test full storage, repeated HTTP errors, clock changes, process death, reboot, and Stop during a request.

### A-04 — ToE evidence came from the designated repo, but retrieval and provenance remain incomplete

**Severity:** P1 · **Reachability:** active in launcher/chat responses.

**Evidence/status:** the designated source is [`jakesdev1991/Omega_Theory_Everything`](https://github.com/jakesdev1991/Omega_Theory_Everything). Its `PROOF_MAP.md`, `PROOF_AUDIT.md`, Lean sources, and CI records provide source provenance and scope formal claims. **The original evidence-to-model defect was partially fixed in PR #2 (merged 2026-10-27):** `ToEReasoningEngine.reason()` now passes bounded retrieved evidence to `FourHorsemenOrchestrator.deliberate(input, referenceContext)`, where it is labeled untrusted evidence rather than instructions/policy/authorization. However, embedded/live asset search still uses a divergent snapshot (2/125 same-named assets were byte-identical to the reviewed repository `main` snapshot; 120 differed and 3 had no match). The GitHub sync still tracks mutable `main`, flattens source paths, and does not pin/cite exact commit/blob/line identity. Consequently “evidence included” is improved, but canonical current-source attribution and the claim that a passage supports the generated answer remain unresolved.

**Scenario:** a user asks a scientific or high-impact question. A keyword match can return an unrelated line from an older bundled snapshot; the passage is now included as untrusted context, but the model may still treat weakly related material as support and the app may attach a filename without establishing claim-level entailment. This can overstate what the source and model jointly support.

**Counterargument/control:** the repo has meaningful formalization, proof-honesty documentation, statement mapping, and a successful Lean CI run on the reviewed main SHA. Its own documentation also warns that a kernel-checked result may be vacuous, model-internal, conditional, or not a physical derivation. Those are strong source controls; they do not make the app's post-hoc citation a valid attribution.

**Remaining recommendation/tests:** keep the user-configured Omega server and any bundled assets source-separated; pin sync to an immutable commit and preserve original path/blob/line provenance instead of flattening files. Already implemented: bounded retrieved excerpts are passed to the council as untrusted context. Still needed: require answer citations to the exact retrieved passages, distinguish “retrieved” from “supports claim,” remove grounding claims on offline fallback, and preserve the repo's own proof-status labels/caveats. Test irrelevant/contradictory/absent hits, stale snapshots, sync failures, path collisions, and prompt-injected passages.

### A-05 — Browser-fetch DNS validation is vulnerable to resolution/connect races

**Severity:** P2 · **Reachability:** active read-only browser and heartbeat fetcher; requires an attacker-controlled/rebinding hostname or equivalent network condition.

**Evidence:** `LuciferBrowser.validatePublicWebUrl()` resolves the hostname and rejects non-public results, then `navigate()` passes the hostname to `HttpURLConnection`, which can resolve it again when connecting (`data/browser/LuciferBrowser.kt`, lines 39–50 and 130–166). Redirects are revalidated, which is a meaningful mitigation, but the checked DNS answer is not pinned to the socket destination. Address classification is also hand-maintained.

**Scenario:** a hostile hostname resolves to a public address during validation and a private/device-local address on the subsequent connection. This can defeat the intended SSRF protection if the runtime resolver/cache/network behavior permits rebinding. Browser reads do not intentionally submit forms, but can still disclose reachable service content to the app/provider.

**Counterargument/control:** HTTPS is required, embedded credentials and local/private/reserved ranges are rejected, redirects are manual and checked, and response bodies/timeouts are bounded. Treat these as defense-in-depth, not a network isolation boundary.

**Recommendation/tests:** use a client that connects to a validated IP while preserving TLS hostname verification/SNI, or enforce egress through a trusted proxy/firewall; validate IPv4/IPv6 special-use ranges comprehensively. Test redirects, mixed public/private DNS answers, rebinding, IPv4-mapped IPv6, unusual numeric host forms, DNS failure, and certificate mismatch. Do not add browser write actions without the user's selected external-write confirmation boundary.

### A-06 — “APPA” integrity ordering can approve untrusted context for wallet signing if wired

**Severity:** P1 · **Reachability:** dormant; `AppaPolicyEngine` has no production callsite in the reviewed Android source.

**Evidence:** `appa/AppaPolicy.kt` declares `Integrity { TRUSTED, INTERNAL, EXTERNAL, UNTRUSTED }` but its `dominates()` treats a higher ordinal as more trusted. `join()` selects the higher ordinal, so combining a trusted user label with web/untrusted input yields `UNTRUSTED`; however, `dominates()` then treats that UNTRUSTED ordinal as satisfying a `requiredIntegrity=TRUSTED` capability. `canAcquire()` also uses `meet()` and checks the origin preserved from the current label. Thus the sample’s intended `canSignBranched == false` is not guaranteed by this algebra; with the declared wallet capability and matching branch values it can evaluate as allowed. No enforcement callsite currently makes this an active wallet bypass.

**Scenario:** a future developer connects this engine as the “security barrier,” retrieves attacker-controlled web text, then trusts its `canAcquire()` result before a wallet or sandbox operation. The policy may accept the tainted branch.

**Counterargument/control:** current transaction sending/signing is not exposed in the active wallet UI, and no active code calls APPA. This limits present impact.

**Recommendation/tests:** do not use the current label algebra for authorization. Replace ordinal comparisons with explicit, documented partial orders and fail-closed policy predicates; keep provenance and purpose checks independent. Add truth-table/property tests for every integrity pair and assert that WEB/MODEL/UNTRUSTED input cannot authorize wallet signing or shell execution. Treat “APPA” as a prototype until those tests pass.

### A-07 — Legacy Kai sandbox file helpers contain shell injection and arbitrary file-write primitives

**Severity:** P1 if reconnected · **Reachability:** dormant/conditional; Android's active UI only performs a Kai ping, and these Python helpers are not wired into the normal UI path.

**Evidence:** `scripts/kai_sandbox/sandbox/sandbox_ops.py` manually wraps argv in double quotes and embeds the result in another `sh -c` (`lines 15–38`). `write_file()` computes an escaped value but interpolates raw `path` and `content` into a shell command/heredoc (`lines 99–105`); an untrusted path can break command syntax, and content can terminate the `EOF` heredoc. The script also exposes read/delete/recursive-delete and unrestricted `exec_shell` actions. Python syntax compilation passed; that does not establish safe execution.

**Scenario:** if a model-controlled path or content reaches `write_file`, shell metacharacters or a terminating heredoc can execute commands in the Kai package context; even without injection, arbitrary path operations can destroy or disclose Kai app files.

**Counterargument/control:** the scripts are outside Android assets/source sets, and current `KaiBridge` methods expect imports under `/tmp/omni_bridge/...`, not these checked-in paths. Current chat does not call them. This is a latent risk, not evidence of an active attack path.

**Recommendation/tests:** remove the dead bridge or isolate it behind a separately reviewed API. Never build a shell command from caller-controlled paths/content; use argument arrays and direct file APIs, canonicalize paths under an allowlisted workspace, cap sizes, and require explicit per-operation approval for writes/deletes. Fuzz quotes, newlines, `$()`, backticks, shell redirections, `EOF`, symlinks, and traversal. Verify the Kai package is debuggable/accessible and that no private app data is exposed before considering this integration usable.

### A-08 — Legacy wallet helper emits plaintext private keys and signs transactions without an app-side policy

**Severity:** P1 if reconnected · **Reachability:** dormant; not used by the current wallet UI.

**Evidence:** `scripts/kai_sandbox/wallet/wallet_ops.py` returns `private_key` from `generate_wallet()` and saves raw wallet JSON (`approximately lines 26–64`); `sign_transaction()` loads that key and signs caller-supplied recipient/value/fee/data/chain fields (`lines 66–105`). No policy layer, typed transaction preview, receipt check, spend cap, or recovery model is enforced by the current Android wallet.

**Scenario:** if the old Kai route is restored or a script result is logged, a private key can leak to an LLM/UI/log; if signing becomes reachable, a caller can request an arbitrary chain/recipient/value and receive a signed transaction.

**Counterargument/control:** current Android `LuciferWallet` stores key material separately under Keystore-backed encryption, exposes no transaction-send method, and the old OmniBridge raw-hash signing request is explicitly rejected. Keep those controls; do not infer them protect the detached Python helper.

**Recommendation/tests:** remove the helper or replace it with a non-exportable signer interface; never serialize keys in results/logs. Require chain ID, recipient, token/amount, calldata, nonce, fee ceilings, simulation, human-readable preview, independent policy checks, and explicit confirmation before signing. Add negative tests for chain mismatch, arbitrary calldata, recipient substitution, token contract mismatch, overspend, and duplicate/replay requests.

### A-09 — Legacy browser/LLM helpers allow unsafe automation and credential exfiltration if reconnected

**Severity:** P1 if reconnected · **Reachability:** dormant; current native browser UI returns “unsupported” for JS actions and does not call these helpers.

**Evidence:** `scripts/kai_sandbox/browser/browser_ops.py` launches Playwright with `--no-sandbox` by default, accepts arbitrary navigation, JS evaluation, cookie reads/writes, and click/type operations, and returns page HTML, links, cookies, and screenshots. `scripts/kai_sandbox/llm/llm_ops.py` accepts a caller-controlled `base_url` and attaches its NVIDIA bearer key to requests, while its “secure prefs” path contains only a decryption placeholder and falls back to environment/argument keys (`llm_ops.py`, approximately lines 8–30 and 70–130). A malicious base URL can receive the key. These helpers are separate from the current fixed-HTTPS `NemotronClient` and native read-only browser.

**Scenario:** connecting arbitrary page content to browser actions can submit a form or expose an authenticated session; pointing the LLM helper at an attacker host can transmit the configured API key in the Authorization header.

**Counterargument/control:** no normal app callsite was found for these Python helpers, they are not packaged as app assets, and current browser click/type/evaluate operations fail closed. Do not attribute this exposure to the active native browser.

**Recommendation/tests:** remove or quarantine the scripts; if reintroduced, use a strict host allowlist, no-sandbox only where required and inside a real OS/container boundary, ephemeral isolated browser contexts, explicit per-write confirmation, no cookie export, and HTTPS endpoint allowlisting with credential binding. Test malicious redirects, page prompt injection, form submission, cookie leakage, and credential-redirection attempts.

### A-10 — The “Evidence Ledger” is not durable, append-only, or cryptographically trustworthy

**Severity:** P2 · **Reachability:** dormant; no production callsite found.

**Evidence:** `evidence_ledger/EvidenceLedger.kt` calls a SHA-256 placeholder “BLAKE3,” keeps records only in mutable in-memory lists, trims records while leaving the Merkle tree inconsistent, and rebuilds the entire tree for each append. Proof verification does not encode left/right sibling direction and fails for ordinary right-child/odd-leaf cases. `ring_buffer/MirroredRingBuffer.kt` uses a repeated-byte FNV placeholder rather than BLAKE3; `AsyncEvidenceConsumer` reads `getContiguousSlice()` without advancing the ring-buffer head, so it repeatedly processes the same records and can eventually leave the producer permanently full (`lines 127–169, 200–239`).

**Scenario:** a future UI reports “tamper-evident audit” based on these classes, but an app restart loses the records; invalid proofs can be accepted/rejected incorrectly, or the consumer stalls while duplicating old events.

**Counterargument/control:** neither component is wired to current wallet/terminal decisions. Current security does not depend on it.

**Recommendation/tests:** remove the security claims until a reviewed cryptographic library, durable append-only storage, canonical serialization/domain separation, signed/checkpointed roots, correct inclusion-proof orientation, bounded incremental updates, and concurrency semantics exist. Add mutation/deletion/reordering/restart/odd-leaf/property tests and a producer/consumer wrap/full stress test.

### A-11 — “Cognitive safety” modules are heuristic/demo code, not usable enforcement or evidence

**Severity:** P2 · **Reachability:** mostly dormant; no callsites found for QSTAR, TARo, GEPA, RCOD, MetaCognitiveRouter, or ContextCompressor in the active app path.

**Evidence:** `QstarRouter` returns canned strings rather than invoking either model; its confidence is based on length and punctuation. `RcodTelemetry.evaluate()` indexes the median of an empty list when there are too few state vectors, and `reasoningToStateVector()` ignores supplied embeddings in favor of a text hash. `GepaEngine`’s sandbox tier always returns true and its “novelty” tier accepts keyword matches/generation count. `ContextCompressor` uses hash-based pseudo-embeddings, and `compressOldest()` removes user messages while its local summary only retains assistant/tool messages. `TaroEngine` masks token IDs but does not guard actual Android tools or shell effects. These are prototypes, not measured cognitive quality, sandboxing, or policy.

**Scenario:** developers or users rely on a displayed “stable,” “audited,” “sandbox passed,” or high-confidence result to approve a shell/wallet action, although the reported metric is heuristic, can crash, or never executed a model/sandbox.

**Counterargument/control:** these components are not in the normal UI execution path today.

**Recommendation/tests:** label as experimental and never feed their outputs into authorization, spending, or safety decisions. Remove production-status wording; add empty-input, adversarial-input, replay, numerical-boundary, and actual-model/sandbox integration tests before reactivation.

### A-12 — Settings expose a Soul editor that can erase/ignore its own contents

**Severity:** P2 · **Reachability:** active Settings UI; its effect on current chat is not established.

**Evidence:** `AnimatedSettingsPanel` initializes the Soul editor with an empty string and saves on each text change. `NemotronClient.systemPrompt()` reads `files/soul.md`, but reviewed active chat paths use role-specific persona prompts (`ask(persona, input)`) and the heartbeat has its own fixed prompt; no active callsite to `askWithSoul()` was found. The bundled `assets/soul.md` also directs “FULL AUTONOMY,” auto-sweeps, production deployment, and crypto-only settlement, which conflicts with the user's stated fiat-plus-crypto inbound direction and no-live-payment boundary. These directives are not currently the heartbeat's operative policy.

**Scenario:** the user believes the editor controls Lucifer's active personality/safety, but it silently saves partial/blank text and may not affect active responses at all. If a future path begins using the asset, its instructions are broader than the agreed operation boundaries.

**Counterargument/control:** heartbeat itself has a separate restrictive system prompt, and no shell/tool execution is granted by the Soul file.

**Recommendation/tests:** load the persisted/asset value into an explicit edit buffer, save only on confirmation, show success/failure and rollback, and identify exactly which model routes consume it. Replace autonomy and payment instructions with the user-approved operation policy. Test empty/cancel/restart/save-failure behavior and verify each active model request's final system policy.

### A-13 — The app's repo sync is mutable, unbounded, and does not faithfully mirror the designated source

**Severity:** P2 · **Reachability:** manual Settings sync; retrieved content is not currently fed into the model's deliberation request.

**Evidence:** the designated source repository exists at [`jakesdev1991/Omega_Theory_Everything`](https://github.com/jakesdev1991/Omega_Theory_Everything); the owner's spelling in the supplied URL omitted the leading `j`, while the app already uses the canonical owner. `EmbeddedToE.syncFromGithub()` queries a recursive tree from mutable `main`, reads every selected response without per-file/aggregate byte caps, and writes to private `files/toe_live/` without commit/hash provenance (`knowledge/EmbeddedToE.kt`, lines 165–225). It flattens repository paths. For `.txt` mirrors, its URL selection routes names such as `Vol08_*.txt` under `lean_proofs/` rather than `txt_proofs/`. A comparison against repo main commit `4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80` found just 2 of 125 app files byte-identical; 120 same-name candidates differed and 3 had no counterpart. The code does not report this divergence or remove stale overlays. Path containment should be explicitly validated even if GitHub's current tree naming constraints make a traversal name difficult to create.

**Scenario:** the user taps Sync expecting current repository evidence, but mirrored files remain stale or downloads fail silently; a large or compromised upstream revision can also consume app storage or replace trusted-looking source material. If the material is later sent to a model, it is untrusted prompt content.

**Counterargument/control:** sync is user-triggered over HTTPS from the intended repository; content is not executed as code. The source repo has its own proof-honesty rules and a green Lean CI run on the reviewed SHA. The app does not currently attach synced passages to its reasoning request, and the repository's latest full CI run had unrelated-to-Lean failures.

**Recommendation/tests:** pin a reviewed commit or verify Git blob hashes/signatures; use a manifest with source path, commit, hash, and expected size; restrict to explicit filenames; preserve source-directory mapping; canonicalize output paths under `toe_live`; cap file count/bytes/time; write atomically; surface partial failures and source revision; and provide rollback. Test mirror paths, stale files, oversized files, duplicate basenames, traversal-like paths, partial failures, and rollback. Continue using the repository's own Lean CI and caveats rather than treating arbitrary bundled text as verified proof.

### A-14 — Native CMake/JNI are not part of the Android build and the declared CMake source path is wrong

**Severity:** P2 · **Reachability:** dormant; Android Gradle does not configure `externalNativeBuild`, and `System.loadLibrary("lucifer_native")` is commented out.

**Evidence:** `native/CMakeLists.txt` names `lucifer_native.cpp` at the CMake root, while the file is under `native/src/lucifer_native.cpp`; the include directory points to `native/../include` while the header is under `native/include`. CMake therefore appears not to have a valid source/header layout. The Gradle app does not package this library, and the application library load is commented out (`LuciferApplication.kt`). The inspected JNI functions are placeholders.

**Scenario:** a future release enables CMake/loadLibrary and fails at configure/link/runtime, or a feature is advertised as native but is never called.

**Counterargument/control:** no active functionality depends on JNI today.

**Recommendation/tests:** either delete/document this abandoned prototype or correct the CMake paths, wire a supported NDK/ABI build, add JNI smoke tests on actual devices, and do not make Java/Kotlin startup depend on the library until packaged ABI coverage is verified.

### A-15 — Remote knowledge-server instructions advertise HTTP that Android blocks

**Severity:** P2 · **Reachability:** user-configured remote knowledge endpoint.

**Evidence:** Settings prompts for `http://IP:8030` (`SettingsPanel.kt`, around line 378), while `network_security_config.xml` permits cleartext only for `10.0.2.2`, `localhost`, and `127.0.0.1`; the base configuration denies it. An ordinary LAN IP over HTTP will therefore be rejected by Android network security. HTTPS is supported by the documented remote path.

**Scenario:** a user follows the UI text for a LAN host, saves an `http://192.168...` endpoint, and the connection silently fails even though the host is reachable.

**Counterargument/control:** loopback/emulator defaults are deliberately allowed; public/remote endpoints should use TLS.

**Recommendation/tests:** change the UI guidance to HTTPS for non-loopback hosts and surface the precise network-security failure. Test loopback, emulator host, private LAN HTTPS, private LAN HTTP rejection, and malformed URL handling.

### A-16 — Build/release reproducibility and Android compatibility are not established

**Severity:** P0 release gate · **Reachability:** every release.

**Evidence:** there are no discovered unit/androidTest files or CI workflow paths. `compileSdk/targetSdk=34`, Gradle 8.5, AGP 8.3.0, Kotlin 1.9.10, several 2023/24 AndroidX versions, and a CMake source not wired to Gradle form the release configuration. Gradle debug is minified as well as release. This workspace has no `java`/`kotlinc` executable, so `./gradlew` compilation, lint, dependency resolution, R8, manifest merge, and APK inspection could not run. The absence of build verification does not prove a compile failure; it means one cannot claim a verified release.

**Scenario:** source changes compile only on the developer's device, an Android 14/15/16 foreground-service restriction blocks heartbeat resume, release R8 strips an AIDL/service class, or a platform/API policy prevents distribution.

**Counterargument/control:** the wrapper/configuration is present, security documentation correctly says no build/device test has run, and current services declare camera/data-sync types and runtime consent flows.

**Recommendation/tests:** establish a JDK 17 + Android SDK CI build for debug and release, lint, dependency/manifest checks, and automated tests. Review target/compile API against current distribution/platform requirements before release. Exercise all foreground-service starts (including boot resume) on target Android versions and OEMs; validate notification/camera indicators, Shizuku service binding, overlay permission, Termux intents, SSH, and AVF guest constraints on real hardware.

## Existing controls that materially reduce risk

- No active wallet broadcast/signing path; private keys are separated from wallet metadata and encrypted with Android Keystore AES-GCM. This protects at-rest confidentiality under the app-process trust model, not recovery or a compromised unlocked process.
- Shell commands pass through natural-language routing. Non-read-only commands require an explicit confirmation with a selected destination; read-only commands can auto-run. Confirmation is not a sandbox: LOCAL is app UID, Termux is its own Linux environment, VM is the configured SSH host, and Shizuku is the identity the user authorized (ADB shell or root, subject to SELinux/platform limits).
- Shizuku permission changes remain outside Lucifer; user starts the Shizuku server and grants Lucifer access. Manifest declaration is not runtime consent.
- Browser UI is a bounded HTTPS GET/text reader, rejects obvious private targets, and does not implement click/type/JS/form submission. It is not an interactive browser and its DNS check is not a hardened network boundary.
- Heartbeat starts only from explicit UI opt-in, has a visible Stop notification, uses a separate camera opt-in/runtime permission, resumes after reboot text-only on a best-effort basis, and writes local drafts only. The model prompt is not the security boundary; the actual code's lack of tool/write interfaces is.
- `allowBackup=false`, app-private storage, app-private FileProvider paths, HTTPS provider requests, encrypted provider/SSH secrets, strict SSH host-key checking, response/time limits in selected paths, and documented key-rotation need are positive controls.
- Restored manifest declarations do not create feature implementation or runtime consent. Most restored storage/Bluetooth/NFC/biometric/local-network permissions have no active callsite in the reviewed source; preserve declarations per user instruction, but describe capability honestly.

## Prioritized action and verification plan

### Before any real funds, public exposure, or release

1. Keep wallet funding disabled until tested recovery exists; then use a separate receiver and a pinned official USDC contract/Arbitrum chain ID. Inbound payment acceptance must not authorize outbound spending.
2. Add model-data disclosure, local-only mode, per-feature egress control, request budget and heartbeat storage retention. Test actual serialized chat, camera, page, and session payloads.
3. Fix ToE evidence flow and remove unsupported grounding/proof claims; independently validate the Lean corpus.
4. Keep the Python Kai helpers disconnected. Remove them or remediate shell injection, plaintext keys, browser `--no-sandbox`, arbitrary JS/cookie operations, and the LLM `base_url` bearer-token risk before any integration.
5. Correct and property-test APPA integrity semantics before using it; do not treat current prototype modules as safeguards.
6. Create CI: `:composeApp:assembleDebug`, `:composeApp:assembleRelease`, lint, unit tests, dependency checks, merged-manifest review, APK/signature/permission inspection. No successful build is claimed in this review.

### Device/platform test matrix

- **Android lifecycle:** camera permission granted/denied/revoked, notification permission denied, FGS start from visible activity, app swipe-away/process kill, system restart, reboot resume, battery saver, low storage, app force-stop, OEM background limits, Stop during an active request; verify text-only reboot behavior and indicator.
- **Termux and overlay:** documented `RUN_COMMAND` permission/configuration, callback/no-callback/timeout, visible PTY, tmux reconnect, output truncation, interactive input, floating overlay persistence/drag/keyboard/Stop, and no calls to localhost from a browser component.
- **VM/Shizuku:** known-host new/matching/changed key, non-default port, wrong fingerprint, password failure, command timeout/output cap; Shizuku unstarted, denied, revoked, ADB shell and root cases, service death/rebind, and `id` test. Verify exact destination/command in confirmation.
- **Wallet:** known secp256k1/Keccak test vectors, persistence/process restart, install/restore/reinstall, Keystore invalidation, chain/symbol correctness, unsupported network/address, RPC error/oversized response, and recovery. Do not use live funds for tests.
- **Network/privacy:** redirect to private IP, DNS rebinding, IPv4/IPv6 edge cases, TLS failure, large/gzip response, invalid base URL, cloud request capture with canary secrets, and evidence that no API key enters logs/history/exports.
- **Data/correctness:** unrelated/misleading corpus hits, empty RCOD history, ledger proof left/right/odd leaves, ring wrap/full/concurrent access, GEPA false-positive audit, GitHub oversized/path-like entries, heartbeat retention/rollback, blank Soul edit/save failure, and offline model fallbacks.

## Verification performed and limitations

- `python3 -m compileall -q scripts/kai_sandbox` completed successfully; generated `__pycache__` files were removed. This validates Python syntax only, not runtime safety or Android reachability.
- `git diff --check` passed after the verifier/UI changes. The embedded detached preparation and verification Bash scripts and their timeout launchers passed `bash -n` syntax checks after expanding their Kotlin string templates. Mock timeout runs produced the expected `PREPARATION_TIMEOUT`/`TIMEOUT` statuses and log markers. The repository's no-`sorry`/unsafe/trivial-stub grep gate also passed against the reviewed Omega Theory checkout. These are static/mock checks only; they do not validate Kotlin compilation, Android SSH behavior, or a local Lean build.
- The original inventory found 60 active-source Kotlin files and no conventional `test`, `androidTest`, or `.github/workflows` paths in this checkout; the on-device verifier addition brings the current tree to 61 Kotlin files.
- Cloned the designated Omega Theory repository at `main` commit `4c5a35b8d5d39a8e494c2c15cd0eba7e1eb2ce80`, inspected its proof-status/audit documentation, checked current GitHub Actions metadata, and compared its tracked knowledge files with the app's 125 bundled ToE assets. The exact main SHA has a successful Lean CI run; the latest full-repository CI run for that SHA failed in dependency-advisory, secret-scan, and MyPy steps. These are external repository checks, not a build or test of Lucifer Mobile.
- No Java/JDK, Kotlin compiler, Android SDK/device, or runtime build was available in this workspace. Lucifer Mobile Gradle/CMake, instrumentation, lifecycle, permission, SSH, and network behavior remain unverified. Lean compilation was not run locally; the cited repository CI result is evidence only for that source revision's GitHub run.
- This update adds the on-device verifier runner, Settings controls, and documentation. The implementation itself has not been compiled or exercised against the Debian VM; neither the app nor the verifier can yet be claimed as working or release-verified.
