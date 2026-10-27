# Lucifer memory: native Termux MCP + on-device episodic store

There are now **two intentionally separate memory sources**:

1. **Remote reference memory:** the owner-curated private Drive file `Lucifer_Memory.md`, read through the owner's `rclone` remote. It is never copied to the phone's disk by the MCP service. The Termux C++ server holds only a short-lived in-memory index and reads Drive when refreshed.
2. **Local episodic memory:** a private SQLite FTS store in Lucifer's app-private files. It stores compact redacted memories of conversations and completed action/findings, not a Drive mirror, raw transcript archive, or image collection. Retention is importance/tier-based, not time-based: entries do not expire by age. The database has a 2 GiB **disk** page limit; it does not reserve 2 GiB of RAM. The store is not backed up by Android because app backup is disabled. The user selected a separate long-term server tier for curated summaries only; that archive write/sync path is not yet implemented.

Before an online Nemotron call, Lucifer searches local episodic memory first, then optionally queries the same-device MCP service for Drive passages. Retrieved text is untrusted context, never policy or authorization. If the MCP service is unavailable, the original prompt remains unchanged apart from locally retrieved memories. Offline, Lucifer can still retrieve local memories and append them to its local/static reasoning response; this does not turn the remote Nemotron model into an offline model.

## Native runtime and model

The Termux runtime is C++17 plus the native C++ `llama-server` from Termux's `llama-cpp` package. The MCP/Drive coordinator is `mcp/cpp/drive_memory_mcp.cpp`; it uses the pinned `cpp-httplib` and `nlohmann-json` headers and launches `rclone` with an argv vector (no shell command interpolation). Python is **not installed or used by the runtime bootstrap**. Python's pinned MCP SDK remains only for host-side protocol tests.

Embeddings use the all-MiniLM-L6-v2 Q4_K_M GGUF model (about 20 MB), downloaded outside the repository. Bootstrap pins the model-package Git commit and verifies the archive SHA-256 before extraction. BM25 ranking remains available if llama.cpp is stopped; when llama.cpp is running, its semantic scores are fused with BM25. Pixel memory/latency has not yet been benchmarked.

This replaces the earlier ALBERT encoders for the Termux retrieval service. llama.cpp does not run the stock ALBERT checkpoints. The compact GGUF was selected for the latest low-memory local-runtime direction; if ALBERT is still a hard requirement, that needs a different runtime.

## Private Drive source

`Lucifer_Memory.md` remains private and is read-only from Lucifer's perspective. The Termux remote path defaults to `gdrive:Lucifer_Memory.md`. Configure rclone with a read-only Google Drive scope if offered. Do not make the file public and do not put API keys, passwords, recovery phrases, or private keys in it. No Drive content is synced into the local episodic SQLite database.

## One-time Termux setup

1. Install/open Termux and configure the private Drive remote:

   ```sh
   pkg update
   pkg install rclone git tmux
   rclone config
   ```

   Create a remote named `gdrive` and complete Google's login flow. Verify the file is visible:

   ```sh
   rclone cat 'gdrive:Lucifer_Memory.md' | head
   ```

2. Fetch the branch and build the native service/model runtime:

   ```sh
   git clone -b arena/1afc468d-lucifer-mobile https://github.com/jakesdev1991/lucifer_mobile.git ~/lucifer_mobile
   cd ~/lucifer_mobile/mcp
   ./bootstrap_termux.sh
   ```

   Bootstrap installs Termux's C++ compiler/CMake, `llama-cpp`, `rclone`-adjacent helpers, and `unzip`; downloads/verifies the pinned Q4 model; builds the C++ MCP executable with two build jobs to limit compile RAM; verifies Drive read access; creates a mode-600 random bearer token at `~/.config/lucifer-memory/server.env`; and prints the token once. Copy it into Lucifer → **Settings → Drive Memory · Local MCP**. Lucifer stores it encrypted with Android Keystore. No Python package or Python server is installed by this flow.

3. Opt into persistent Termux services:

   ```sh
   ./install_termux_service.sh
   ```

   This installs/enables two runit services: `lucifer-embeddings` (C++ llama.cpp on loopback port 8032) and `lucifer-memory` (native C++ MCP on loopback port 8031). It also creates `~/.termux/boot/20-lucifer-memory-services` for Termux:Boot.

   For reboot startup, install and open the **Termux:Boot** companion from the same trusted distribution source as Termux, then exempt Termux from Android battery optimization. Boot startup is best-effort: Android can still stop a force-stopped app or apply vendor power policies. The Termux wake lock improves persistence but uses more battery. To stop/disable services:

   ```sh
   sv down lucifer-memory lucifer-embeddings
   sv-disable lucifer-memory lucifer-embeddings
   termux-wake-unlock
   ```

4. Verify both local listeners and configure Lucifer:

   ```sh
   sv status lucifer-embeddings lucifer-memory
   curl -fsS http://127.0.0.1:8032/health
   curl -fsS http://127.0.0.1:8031/health
   ```

   In Settings, use `http://127.0.0.1:8031/mcp`, paste the printed token, save, then **TEST READ-ONLY MCP CONNECTION**. Both listeners bind to loopback only; no LAN/public listener is created. The server currently exposes only `search_memory`, `memory_status`, and `reload_memory` read-only tools.

## Local episodic memory

- Stored in Lucifer's app-private `local_episodic_memory.db`, not in Termux and not in Drive.
- The local store keeps compact extractive summaries of completed chat exchanges and outcomes of commands that actually ran. It does not store images, whole transcripts, or Drive files. Secret-like patterns are redacted before insertion.
- The user selected importance/tier-based retention instead of fixed TTLs. Explicit decisions and durable findings rank above routine exchanges; entries do not expire due to age. The user can pause future saving or clear the database in **Settings → LOCAL EPISODIC MEMORY · OFFLINE**.
- SQLite FTS keeps the corpus disk-backed and searches only matching rows. The database's page limit is 2 GiB; this is a maximum storage quota, not a RAM allocation. No automatic compaction/offload-to-server write path is currently enabled; the user selected a separate long-term tier containing curated summaries only.
- Android app-private storage is protected by the OS sandbox. No external storage, cloud sync, or app backup is used. Do not assume this is equivalent to an independently encrypted vault.

## Data and safety boundaries

- The Drive MCP server has no Drive write/share tool and does not persist a cloud mirror. Its brief in-memory cache is only for active online retrieval.
- User prompts and selected Drive passages may still be sent to the configured NVIDIA endpoint when the user requests a model response. Local episodic memories remain on-device, but relevant ones are inserted into prompts sent to that configured model. Do not store secrets or sensitive data unless this provider/data flow is acceptable.
- Retrieved memory is historical data and may be stale or instruction-like. It never grants permissions or authorizes shell, wallet, browser, or external actions.
- MCP requires a random bearer token. The token is mode-600 in Termux and encrypted in Android Keystore. `/health` reveals only minimal local service status.

## Verification and limitations

The Termux server is C++; Python is only used on a development machine for the official MCP protocol test client:

```sh
python3 -m venv .venv
.venv/bin/pip install -r mcp/requirements.txt
cmake -S mcp/cpp -B mcp/build -DCMAKE_BUILD_TYPE=Release
cmake --build mcp/build --parallel 2
LUCIFER_MCP_CPP_BINARY="$PWD/mcp/build/lucifer_memory_mcp" \
  .venv/bin/python -m unittest discover -s mcp -p 'test_*.py' -v
```

This workspace can compile the C++ service directly and validate the official MCP handshake against a local rclone fixture. The Termux package build, real Drive authorization, boot persistence, and Pixel RAM/latency still require on-device validation. Android APK compilation remains unverified here because Java is unavailable.
