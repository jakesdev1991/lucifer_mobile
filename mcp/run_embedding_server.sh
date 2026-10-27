#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

CONFIG_DIR="${LUCIFER_MEMORY_CONFIG_DIR:-$HOME/.config/lucifer-memory}"
ENV_FILE="$CONFIG_DIR/server.env"
if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing $ENV_FILE. Run bootstrap_termux.sh first." >&2
    exit 2
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

MODEL_PATH="${LUCIFER_EMBEDDING_MODEL_PATH:-}"
if [[ -z "$MODEL_PATH" || ! -f "$MODEL_PATH" ]]; then
    echo "Embedding GGUF not found at '$MODEL_PATH'. Add a compatible GGUF embedding model before starting llama.cpp." >&2
    exit 2
fi
if ! command -v llama-server >/dev/null; then
    echo "llama-server missing; install it with: pkg install llama-cpp" >&2
    exit 2
fi

exec llama-server \
    --model "$MODEL_PATH" \
    --alias "${LUCIFER_EMBEDDING_MODEL:-all-MiniLM-L6-v2-Q4_K_M}" \
    --host 127.0.0.1 \
    --port 8032 \
    --embedding \
    --pooling mean \
    --ctx-size "${LUCIFER_EMBEDDING_CTX:-512}" \
    --threads "${LUCIFER_EMBEDDING_THREADS:-4}" \
    --batch-size "${LUCIFER_EMBEDDING_BATCH:-16}" \
    --ubatch-size "${LUCIFER_EMBEDDING_UBATCH:-16}"
