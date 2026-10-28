# Security notes

## Credentials

- Never put provider credentials, wallet private keys, or SSH passwords in source, assets, logs, or issue reports.
- NVIDIA API credentials and VM SSH passwords are stored as AES-GCM ciphertext using a non-exportable Android Keystore key. Legacy plaintext files are migrated on first read.
- Wallet private keys are stored separately from wallet metadata and encrypted with the same Keystore-backed store. The current wallet UI has no recovery phrase/export; do not fund these wallets.
- VM SSH uses JSch `StrictHostKeyChecking=yes` and an app-private `known_hosts` file. Configure only a host key whose fingerprint was verified out-of-band; changed keys must fail closed.
- Android app backup is disabled, and the FileProvider exposes only explicitly staged export/share directories.

**Credential rotation required:** previous revisions of this repository contained NVIDIA API tokens. Removing them from the current source does not remove them from Git history or old APKs. Revoke/rotate every previously committed token in the NVIDIA console and install a fresh key through Settings before using the LLM.

## Background automation

The current heartbeat is a private foreground service. The user must tap Start and grant notification permission where required; camera is a separate optional in-app action with its own runtime consent. After explicit opt-in, a boot receiver attempts to restart the service text-only. Each session can read two fixed public HTTPS trend pages, capture one still with Android's camera privacy indicator active, send the image and page text to NVIDIA's multimodal Nemotron endpoint, and create a local Markdown idea/project draft. Lucifer does not persist the image. The service cannot execute generated commands, deploy, publish, submit forms, contact people, spend money, or write outside app-private `files/heartbeat/`. A persistent notification provides Stop. It waits about 30 minutes after a session finishes; Android can defer or stop it, so exact timing is not guaranteed. An opted-in service attempts to resume after reboot or a sticky service restart in text-only mode; camera capture remains off until the user opens the app and explicitly re-enables camera access. Sessions incur model-provider usage and may cost money.

The **General** is a second user-started foreground service (`agents/android/TacticianService`). It has no execution path: it reads summaries and metadata the app already stores, writes a local brief, and records proposals that only the user can approve in the foreground. It cannot edit prompt files, change permissions, spend money, or contact anyone. Its model calls are capped per day with quiet hours, and its observation ledger is bounded and redacted. Prompt files (`soul.md`, `heartbeat.md`, every `agent-class.md`) are instructions that are sent to the model on each call, so writes reject obvious credentials; they are not a secret store, and the files are unencrypted in app-private storage. Sensing is limited to app-usage events Lucifer itself records — no message content, browsing history, or other apps' private data — and camera sensing is limited to a boolean that a session used the camera, never the image.

Interactive browser automation and external website writes are not yet implemented. The user has selected a human-confirmation boundary before any external submission, post, message, or purchase. Preserve that boundary when building an automation engine; do not treat manifest declarations or prior camera consent as blanket consent for unrelated actions.

## Remaining hardening work

This app can execute shell commands by design. Treat command execution, fetched web content, and all external/model-provided content as untrusted. Before using it with valuable accounts or production infrastructure, device-test the SSH host-key pinning path and add a user-confirmation boundary for high-impact operations such as transaction signing (transaction sending is not currently exposed).
