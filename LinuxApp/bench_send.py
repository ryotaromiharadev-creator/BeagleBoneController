#!/usr/bin/env python3
"""
Benchmark sender: fires N packets at ~500 Hz via UDP loopback.
Packet layout (6 bytes): [seq: uint16 BE][send_us: uint32 BE]
  seq     – wrapping sequence number
  send_us – microseconds since start (wraps at ~4295 s)
"""
import argparse
import socket
import struct
import sys
import time

BENCH_PORT = 9001


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("host",    nargs="?", default="127.0.0.1")
    ap.add_argument("--port",  type=int,  default=BENCH_PORT)
    ap.add_argument("--hz",    type=int,  default=500)
    ap.add_argument("--count", type=int,  default=5000)
    args = ap.parse_args()

    sock     = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    interval = 1.0 / args.hz
    addr     = (args.host, args.port)

    print(f"[bench_send] {args.count} pkts @ {args.hz} Hz → {addr}")

    start     = time.perf_counter()
    next_fire = start
    sent      = 0

    while sent < args.count:
        now = time.perf_counter()
        if now >= next_fire:
            seq    = sent & 0xFFFF
            ts_us  = int((now - start) * 1_000_000) & 0xFFFF_FFFF
            sock.sendto(struct.pack(">HI", seq, ts_us), addr)
            sent     += 1
            next_fire += interval
        # busy-wait to hit 500 Hz on Python without oversleeping

    elapsed = time.perf_counter() - start
    print(f"[bench_send] done: {sent} pkts in {elapsed:.3f}s ({sent/elapsed:.0f} pps)")
    sock.close()


if __name__ == "__main__":
    main()
