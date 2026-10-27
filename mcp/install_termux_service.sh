#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SERVICE_ROOT="$PREFIX/var/service"
BOOT_DIR="$HOME/.termux/boot"

[[ -f "$HOME/.config/lucifer-memory/server.env" ]] || {
    echo "Run bootstrap_termux.sh first so the private token and model path exist." >&2
    exit 2
}
command -v sv-enable >/dev/null 2>&1 || pkg install -y termux-services
export SVDIR="$SERVICE_ROOT"
export LOGDIR="$PREFIX/var/log"
# termux-services' supported runit bootstrap; safe to source again in Termux:Boot.
# shellcheck disable=SC1091
source "$PREFIX/etc/profile.d/start-services.sh"

install_service() {
    local name="$1" launcher="$2" directory="$SERVICE_ROOT/$1"
    mkdir -p "$directory"
    cat > "$directory/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec 2>&1
exec "$ROOT/$launcher"
EOF
    chmod 700 "$directory/run"
    sv-enable "$name"
    sv up "$name" || true
}

install_service lucifer-embeddings run_embedding_server.sh
install_service lucifer-memory run_memory_server.sh

mkdir -p "$BOOT_DIR"
cat > "$BOOT_DIR/20-lucifer-memory-services" <<'BOOT'
#!/data/data/com.termux/files/usr/bin/sh
# Run only after the owner installs Termux:Boot and explicitly enables this service.
SERVICE_ROOT="$PREFIX/var/service"
if [ ! -e "$SERVICE_ROOT/lucifer-memory/down" ] || [ ! -e "$SERVICE_ROOT/lucifer-embeddings/down" ]; then
    termux-wake-lock
    export SVDIR="$SERVICE_ROOT"
    export LOGDIR="$PREFIX/var/log"
    (service-daemon start >/dev/null 2>&1 &)
fi
BOOT
chmod 700 "$BOOT_DIR/20-lucifer-memory-services"

cat <<EOF
Installed and enabled runit services:
  lucifer-embeddings (llama.cpp, loopback port 8032)
  lucifer-memory (MCP server, loopback port 8031)

Service status:
  sv status lucifer-embeddings lucifer-memory
Stop temporarily:
  sv down lucifer-memory lucifer-embeddings
Disable automatic service start:
  sv-disable lucifer-memory lucifer-embeddings

For reboot startup, install the matching Termux:Boot companion app from the same trusted source as Termux, open it once, and exempt Termux from battery optimization. This script added $BOOT_DIR/20-lucifer-memory-services. Android can still stop a force-stopped app or enforce vendor power policies; wake-lock persistence increases battery use.
EOF
SH
chmod +x mcp/install_termux_service.sh