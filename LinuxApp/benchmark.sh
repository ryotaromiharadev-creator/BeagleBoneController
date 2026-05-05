#!/bin/bash
# C vs Python UDP receiver benchmark
# Sends 5000 packets at 500 Hz over loopback and compares performance.

set -e
cd "$(dirname "$0")"

PACKETS=5000
HZ=500
WARMUP_PKTS=100

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║         C vs Python UDP Receiver Benchmark                ║"
echo "║  ${PACKETS} packets @ ${HZ} Hz  (loopback 127.0.0.1)              ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# Build C programs
echo "[bench] Building C binaries..."
make -s

# ── Python receiver ──────────────────────────────────────────────
echo "[bench] Round 1: Python receiver"
python3 bench_recv.py $PACKETS &
RECV_PID=$!
sleep 0.3
python3 bench_send.py 127.0.0.1 --hz $HZ --count $PACKETS
wait $RECV_PID

echo ""
sleep 1

# ── C receiver ───────────────────────────────────────────────────
echo "[bench] Round 2: C receiver"
./bench_recv $PACKETS &
RECV_PID=$!
sleep 0.3
python3 bench_send.py 127.0.0.1 --hz $HZ --count $PACKETS
wait $RECV_PID

echo ""
echo "Done. See numbers above for comparison."
