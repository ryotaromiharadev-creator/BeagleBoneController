#!/usr/bin/env python3
"""
Python UDP RC server — logic-equivalent to rc_server.c.
Used as the benchmark baseline (Python vs C comparison).

Run: python3 rc_server.py
"""
import signal
import socket
import struct
import sys

RC_PORT = 9000
_running = True


def _stop(signum, frame):
    global _running
    _running = False


signal.signal(signal.SIGINT,  _stop)
signal.signal(signal.SIGTERM, _stop)

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("", RC_PORT))
sock.settimeout(1.0)

print(f"[rc_server.py] UDP port {RC_PORT} ready")

while _running:
    try:
        data, _ = sock.recvfrom(2)
    except socket.timeout:
        continue
    if len(data) != 2:
        continue
    throttle, steering = struct.unpack("bb", data)
    print(f"throttle={throttle:4d}  steering={steering:4d}")
    sys.stdout.flush()

sock.close()
print("[rc_server.py] stopped")
