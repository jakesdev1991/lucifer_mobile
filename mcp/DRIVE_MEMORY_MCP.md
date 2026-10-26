# Private Google Drive memory MCP (Termux)

This adds a **read-only, loopback-only MCP server** for the user-curated Google Drive file `Lucifer_Memory.md` (created in the user's Drive). The server runs in Termux on the Pixel and reads the private file through the user's own `rclone` Google Drive remote. It never edits/shares the Drive file and no Drive credentials, private file ID, or bearer token are committed to Git.

The Android `NemotronClient` performs retrieval **before** its main text or image-grounded model request. It sends only the latest user text (up to 2,000 characters) to `http://127.0.0.1:8031/mcp` over device loopback, using Streamable HTTP MCP and the locally stored bearer token. Up to 6,000 characters of retrieved passage text are added as clearly untrusted reference context. The MCP server has only `search_memory`, `memory_status`, and `reload_memory` read-only tools. It binds to `127.0.0.1`, requires a random bearer token, and records no prompt/file contents in access logs. If MCP is unset, unavailable, unauthorized, or errors, Lucifer sends the original prompt unchanged. Retrieval does not grant tool permissions or alter existing action confirmations.

## The Drive memory file

The file was created in the user's My Drive as `Lucifer_Memory.md` (text/markdown). It contains an initial, source-attributed project-memory seed and explicit rules against storing secrets or treating recalled content as policy. Its local seed copy is git-ignored and must not be committed; the Drive copy remains private. Edit it in Drive to add/correct durable facts; avoid API keys, passwords, recovery phrases, and private keys. The server refreshes from Drive on a 30-second cache; `reload_memory` forces a refresh. The Termux remote path defaults to `gdrive:Lucifer_Memory.md`.

## One-time Termux setup

1. Install/open Termux and configure its private Google Drive remote. Google Drive credentials stay inside Termux's rclone config, not this repository:

   ```sh
   pkg update
   pkg install python rclone tmux git
   rclone config
   ```

   Create a remote named `gdrive`, choose Google Drive, and choose a **read-only** scope if rclone offers the choice. Complete Google's own login flow. Verify the private file is visible:

   ```sh
   rclone cat 'gdrive:Lucifer_Memory.md' | head
   ```

2. Fetch the branch and bootstrap the server:

   ```sh
   git clone -b arena/1afc468d-lucifer-mobile https://github.com/jakesdev1991/lucifer_mobile.git ~/lucifer_mobile
   cd ~/lucifer_mobile/mcp
   ./bootstrap_termux.sh
   ```

   Bootstrap verifies that rclone can read the selected file, installs the pinned MCP SDK, creates a private mode-600 `~/.config/lucifer-memory/server.env`, generates a random token, and prints that token **once**. Copy it into Lucifer → **Settings → Drive Memory · Local MCP**. The app stores it encrypted with Android Keystore. Keep the Termux env file private; do not paste its contents into chat or commit it.

3. Start the server in Termux:

   ```sh
   tmux new-session -d -s lucifer-memory ~/lucifer_mobile/mcp/run_memory_server.sh
   curl http://127.0.0.1:8031/health
   ```

   The MCP listener is localhost-only; it is not a LAN or public service. Start it again after Termux stops it or Android reclaims Termux. Boot auto-start is intentionally not enabled. In Lucifer Settings, use endpoint `http://127.0.0.1:8031/mcp`, enter the printed token, save, then **TEST READ-ONLY MCP CONNECTION**. Remove the endpoint/token to disable retrieval.

## ALBERT retrieval tier

The server defaults to two small ALBERT encoders (`albert-base-v2` and `albert-large-v2`). It mean-pools their hidden states and combines their independent similarity rankings with BM25 lexical retrieval using reciprocal-rank fusion. Models load on CPU and download/cache in Termux's home directory; no weights are bundled in the APK or repository. The first load may take time and use network/data/storage.

The ALBERT extra is optional because Termux's Python/PyTorch compatibility depends on the device and repository. Install `requirements-albert.txt` only after a compatible CPU PyTorch build is available in that Termux environment. If the model runtime cannot load both encoders, the MCP server reports that status and uses BM25 rather than pretending ALBERT ran. The stock ALBERT checkpoints are not retrieval-fine-tuned sentence encoders, so their pooled-vector similarities are experimental ranking signals, not truth or guaranteed semantic recall. Keep BM25 in the fusion and review retrieved passages.

## Data and safety boundaries

- Drive content is read-only from Lucifer's perspective. `rclone` is configured by the owner; use least-privilege read-only Drive access. Do not make the memory file public.
- Prompt text reaches the **same-device Termux process** for local retrieval, then relevant memory passages may be sent to the configured NVIDIA endpoint along with the prompt. Do not add sensitive data unless that provider/data flow is acceptable.
- Retrieved file text is untrusted and can be stale, mistaken, or instruction-like. The prompt wrapper treats it as context only; active capabilities and confirmation checks remain authoritative.
- The local bearer token is held by the app in encrypted storage and by Termux in a mode-600 env file. The server requires it on `/mcp`; `/health` exposes only a minimal local health/retrieval status.
- No write, file-sharing, shell, wallet, or external-action tool is exposed through this MCP server.

## Verification and current limitations

From the repository root, install the pinned SDK and run the tests:

```sh
python3 -m venv .venv
.venv/bin/pip install -r mcp/requirements.txt
.venv/bin/python -m py_compile mcp/drive_memory_server.py
.venv/bin/python -m unittest discover -s mcp -p 'test_*.py' -v
```

The suite covers chunking, BM25 retrieval, secret-like line redaction, unmatched queries, bearer authentication, and the official MCP Streamable HTTP handshake/tools over a local fixture. A live Google Drive/rclone auth flow and the ALBERT/PyTorch model runtime cannot be verified in this workspace. Android compilation is also unavailable here because Java is missing. Until the Termux setup and Lucifer Settings connection test pass, the app correctly behaves as if memory retrieval is not configured.
