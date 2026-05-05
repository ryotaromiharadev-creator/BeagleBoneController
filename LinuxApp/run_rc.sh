#!/bin/bash
# Start the RC server with mDNS advertisement.
# On BeagleBone Blue, run as: bash run_rc.sh

set -e
cd "$(dirname "$0")"

# Build if not already built
if [ ! -f rc_server ]; then
    echo "[run_rc] Building rc_server..."
    make rc_server
fi

# Start mDNS advertiser in background
python3 mdns_rc.py &
MDNS_PID=$!

cleanup() {
    echo ""
    kill "$MDNS_PID" 2>/dev/null || true
    wait "$MDNS_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "[run_rc] Starting RC server (Ctrl+C to stop)"
./rc_server
