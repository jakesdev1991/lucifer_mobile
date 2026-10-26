#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${LUCIFER_MEMORY_CONFIG_DIR:-$HOME/.config/lucifer-memory}"
ENV_FILE="$CONFIG_DIR/server.env"

command -v python >/dev/null || { echo "Install Python in Termux first: pkg install python" >&2; exit 2; }
command -v rclone >/dev/null || { echo "Install rclone in Termux first: pkg install rclone" >&2; exit 2; }

# Confirm the user's own, read-only Drive remote and the exact memory file.
RCLONE_PATH="${LUCIFER_MEMORY_RCLONE_PATH:-gdrive:Lucifer_Memory.md}"
rclone cat "$RCLONE_PATH" >/dev/null || {
    echo "Could not read '$RCLONE_PATH'. Configure the private gdrive remote with rclone config and retry." >&2
    exit 2
}

mkdir -p "$CONFIG_DIR" "$ROOT/.venv"
chmod 700 "$CONFIG_DIR"
python -m venv "$ROOT/.venv"
"$ROOT/.venv/bin/python" -m pip install --upgrade pip
"$ROOT/.venv/bin/python" -m pip install -r "$ROOT/requirements.txt"

TOKEN="$(python - <<'PY'
import secrets
print(secrets.token_urlsafe(36))
PY
)"
cat > "$ENV_FILE" <<EOF
LUCIFER_MEMORY_RCLONE_PATH="$RCLONE_PATH"
LUCIFER_MEMORY_MCP_TOKEN="$TOKEN"
LUCIFER_MEMORY_MCP_PORT="8031"
LUCIFER_ALBERT_MODELS="albert-base-v2,albert-large-v2"
LUCIFER_ALBERT_ENABLED="1"
EOF
chmod 600 "$ENV_FILE"

cat <<EOF
Configured private, read-only Drive memory source: $RCLONE_PATH
MCP listener: http://127.0.0.1:8031/mcp (loopback only)
Bearer token (copy now into Lucifer → Settings → Drive Memory · Local MCP):
$TOKEN

The token is also stored mode 600 in: $ENV_FILE
Start the server with: $ROOT/run_memory_server.sh
The first ALBERT model download can take time. If Termux lacks a compatible PyTorch runtime,
MCP remains available with BM25 retrieval and reports ALBERT as unavailable; it will not claim ALBERT is active.
EOF
