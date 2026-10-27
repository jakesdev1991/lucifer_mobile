#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${LUCIFER_MEMORY_CONFIG_DIR:-$HOME/.config/lucifer-memory}"
ENV_FILE="$CONFIG_DIR/server.env"
MODEL_DIR="${LUCIFER_EMBEDDING_MODEL_DIR:-$HOME/.local/share/lucifer-memory/models}"
MODEL_PATH="${LUCIFER_EMBEDDING_MODEL_PATH:-$MODEL_DIR/all-MiniLM-L6-v2-Q4_K_M.gguf}"
MODEL_REPO_DIR="${LUCIFER_EMBEDDING_MODEL_REPO_DIR:-$HOME/.cache/lucifer-memory/all-minilm-gguf}"
MODEL_REPO="https://github.com/prakashsundar18/all-minilm-l6-v2-gguf.git"
MODEL_REPO_COMMIT="37904722333055aea5d07047c0c3a7c026516754"
MODEL_ARCHIVE="all-minilm-l6-v2-q4_k_m.zip"
MODEL_ARCHIVE_SHA256="1f81e6e401f7e24ab137fa5a44ad449c05de3b9a2aa1fd40093fb4c8a0fbee5d"

command -v rclone >/dev/null || { echo "Install rclone in Termux first: pkg install rclone" >&2; exit 2; }
pkg install -y llama-cpp unzip git cmake clang make coreutils
command -v llama-server >/dev/null || { echo "llama-server is missing after installing llama-cpp." >&2; exit 2; }
command -v unzip >/dev/null || { echo "unzip is required to unpack the small GGUF model archive." >&2; exit 2; }

# Confirm the user's own, read-only Drive remote and the exact memory file.
RCLONE_PATH="${LUCIFER_MEMORY_RCLONE_PATH:-gdrive:Lucifer_Memory.md}"
rclone cat "$RCLONE_PATH" >/dev/null || {
    echo "Could not read '$RCLONE_PATH'. Configure the private gdrive remote with rclone config and retry." >&2
    exit 2
}

# Download a compact, retrieval-trained, Q4-quantized GGUF embedding model.
if [[ ! -f "$MODEL_PATH" ]]; then
    mkdir -p "$MODEL_DIR"
    mkdir -p "$(dirname "$MODEL_REPO_DIR")" "$MODEL_REPO_DIR"
    if [[ ! -d "$MODEL_REPO_DIR/.git" ]]; then
        git -C "$MODEL_REPO_DIR" init
    fi
    if git -C "$MODEL_REPO_DIR" remote get-url origin >/dev/null 2>&1; then
        git -C "$MODEL_REPO_DIR" remote set-url origin "$MODEL_REPO"
    else
        git -C "$MODEL_REPO_DIR" remote add origin "$MODEL_REPO"
    fi
    git -C "$MODEL_REPO_DIR" fetch --depth 1 origin "$MODEL_REPO_COMMIT"
    git -C "$MODEL_REPO_DIR" checkout --detach FETCH_HEAD
    if [[ ! -f "$MODEL_REPO_DIR/$MODEL_ARCHIVE" ]]; then
        echo "Pinned model archive was not found in the expected upstream commit." >&2
        exit 2
    fi
    ACTUAL_SHA256="$(sha256sum "$MODEL_REPO_DIR/$MODEL_ARCHIVE" | cut -d ' ' -f1)"
    [[ "$ACTUAL_SHA256" == "$MODEL_ARCHIVE_SHA256" ]] || { echo "Model archive SHA-256 mismatch: $ACTUAL_SHA256" >&2; exit 2; }
    unzip -o "$MODEL_REPO_DIR/$MODEL_ARCHIVE" -d "$MODEL_DIR"
fi
if [[ ! -f "$MODEL_PATH" ]]; then
    echo "Expected embedding GGUF was not found at '$MODEL_PATH'." >&2
    exit 2
fi

mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"

# Build the MCP/ retrieval coordinator as native C++17; Python is not a runtime dependency.
cmake -S "$ROOT/cpp" -B "$ROOT/build" -DCMAKE_BUILD_TYPE=Release -DFETCHCONTENT_QUIET=ON
cmake --build "$ROOT/build" --parallel 2

TOKEN="$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
{
    printf 'LUCIFER_MEMORY_RCLONE_PATH=%q\n' "$RCLONE_PATH"
    printf 'LUCIFER_MEMORY_MCP_TOKEN=%q\n' "$TOKEN"
    printf 'LUCIFER_MEMORY_MCP_PORT=%q\n' "8031"
    printf 'LUCIFER_EMBEDDING_URL=%q\n' "http://127.0.0.1:8032/v1/embeddings"
    printf 'LUCIFER_EMBEDDING_MODEL=%q\n' "all-MiniLM-L6-v2-Q4_K_M"
    printf 'LUCIFER_EMBEDDING_MODEL_PATH=%q\n' "$MODEL_PATH"
    printf 'LUCIFER_EMBEDDING_BATCH_SIZE=%q\n' "16"
    printf 'LUCIFER_EMBEDDING_TIMEOUT_SECONDS=%q\n' "2"
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"

cat <<EOF
Configured private, read-only Drive source: $RCLONE_PATH
llama.cpp model: $MODEL_PATH ($(du -h "$MODEL_PATH" | cut -f1))
MCP listener: http://127.0.0.1:8031/mcp (loopback only)
Embedding listener: http://127.0.0.1:8032/v1/embeddings (loopback only)
Bearer token (copy now into Lucifer → Settings → Drive Memory · Local MCP):
$TOKEN

The token is also stored mode 600 in: $ENV_FILE
Start llama.cpp first: $ROOT/run_embedding_server.sh
Then start MCP: $ROOT/run_memory_server.sh
EOF
