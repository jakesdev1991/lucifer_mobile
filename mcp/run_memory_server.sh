#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${LUCIFER_MEMORY_CONFIG_DIR:-$HOME/.config/lucifer-memory}"
ENV_FILE="$CONFIG_DIR/server.env"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing $ENV_FILE. Follow mcp/DRIVE_MEMORY_MCP.md to configure rclone and the local token." >&2
    exit 2
fi
# shellcheck disable=SC1090
source "$ENV_FILE"
: "${LUCIFER_MEMORY_MCP_TOKEN:?Set a random local bearer token in $ENV_FILE}"
export HF_HOME="${HF_HOME:-$HOME/.cache/lucifer-memory/huggingface}"
mkdir -p "$HF_HOME"

if [[ ! -x "$ROOT/.venv/bin/python" ]]; then
    echo "Create the environment first: python -m venv '$ROOT/.venv' && '$ROOT/.venv/bin/pip' install -r '$ROOT/requirements.txt'" >&2
    exit 2
fi
exec "$ROOT/.venv/bin/python" "$ROOT/drive_memory_server.py"
