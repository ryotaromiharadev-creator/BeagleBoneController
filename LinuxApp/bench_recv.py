#!/usr/bin/env python3
"""
Python benchmark receiver — logic-equivalent to bench_recv.c.
Reports parse latency, inter-packet jitter, and CPU usage.

Run: python3 bench_recv.py [packet_count]
"""
import gc
import math
import resource
import socket
import struct
import sys
import time

BENCH_PORT    = 9001
EXPECTED_INTV = 2e-3   # 500 Hz → 2 ms


def main() -> None:
    total = int(sys.argv[1]) if len(sys.argv) > 1 else 5000

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", BENCH_PORT))
    sock.settimeout(15.0)

    print(f"[bench_recv.py] waiting for {total} packets on port {BENCH_PORT}...")

    proc_ns  = []
    jitter_us = []
    prev_ts  = None
    received = 0

    gc.disable()   # suppress GC pauses during the hot loop
    ru_start = resource.getrusage(resource.RUSAGE_SELF)

    while received < total:
        try:
            data, _ = sock.recvfrom(6)
        except socket.timeout:
            print("[bench_recv.py] timeout – did bench_send.py finish?")
            break
        ts_recv = time.perf_counter()

        if len(data) != 6:
            continue

        # same parse work as C version
        seq, ts_us = struct.unpack(">HI", data)
        _ = seq, ts_us

        ts_done = time.perf_counter()
        proc_ns.append((ts_done - ts_recv) * 1e9)

        if prev_ts is not None:
            jitter_us.append(abs(ts_recv - prev_ts - EXPECTED_INTV) * 1e6)

        prev_ts  = ts_recv
        received += 1

    ru_end = resource.getrusage(resource.RUSAGE_SELF)
    gc.enable()
    sock.close()

    if received == 0:
        print("No packets received.")
        return

    # --- statistics ---
    mean_proc   = sum(proc_ns) / len(proc_ns)
    max_proc    = max(proc_ns)

    mean_jitter = sum(jitter_us) / len(jitter_us) if jitter_us else 0.0
    max_jitter  = max(jitter_us) if jitter_us else 0.0
    variance    = sum((x - mean_jitter) ** 2 for x in jitter_us) / len(jitter_us) if jitter_us else 0.0
    stddev      = math.sqrt(variance)

    cpu_us = (
        (ru_end.ru_utime - ru_start.ru_utime) +
        (ru_end.ru_stime - ru_start.ru_stime)
    ) * 1e6

    print()
    print("┌─────────────────────────────────────────┐")
    print(f"│  Python receiver — {received} packets received  │")
    print("├─────────────────────────────────────────┤")
    print("│ Parse latency (per packet)              │")
    print(f"│   mean : {mean_proc:7.0f} ns                    │")
    print(f"│   max  : {max_proc:7.0f} ns                    │")
    print("├─────────────────────────────────────────┤")
    print("│ Inter-packet jitter  |actual − 2 ms|   │")
    print(f"│   mean : {mean_jitter:7.1f} µs                    │")
    print(f"│   max  : {max_jitter:7.1f} µs                    │")
    print(f"│   σ    : {stddev:7.1f} µs                    │")
    print("├─────────────────────────────────────────┤")
    print(f"│ CPU time (user+sys)  : {cpu_us/1e3:6.1f} ms        │")
    print(f"│ CPU/packet           : {cpu_us/received:6.1f} µs        │")
    print("└─────────────────────────────────────────┘")


if __name__ == "__main__":
    main()
