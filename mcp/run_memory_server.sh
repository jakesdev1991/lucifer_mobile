#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${LUCIFER_MEMORY_CONFIG_DIR:-$HOME/.config/lucifer-memory}"
ENV_FILE="$CONFIG_DIR/server.env"
BINARY="$ROOT/build/lucifer_memory_mcp"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Missing $ENV_FILE. Follow mcp/DRIVE_MEMORY_MCP.md to configure rclone and the local token." >&2
    exit 2
fi
if [[ ! -x "$BINARY" ]]; then
    echo "Native C++ MCP binary missing. Run bootstrap_termux.sh to build it." >&2
    exit 2
fi
# shellcheck disable=SC1090
source "$ENV_FILE"
: "${LUCIFER_MEMORY_MCP_TOKEN:?Set a random local bearer token in $ENV_FILE}"
exec "$BINARY"
