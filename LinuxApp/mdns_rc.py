#!/usr/bin/env python3
"""
mDNS advertiser for the C-based RC server.
Run alongside rc_server so Android's auto-discovery can find it.

Usage: python3 mdns_rc.py [port]   (default port 9000)
"""
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".packages"))

import asyncio
import socket
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

SERVICE_TYPE = "_linuxconnect._tcp.local."
SERVICE_NAME = "BeagleBoneRC"
RC_PORT      = int(sys.argv[1]) if len(sys.argv) > 1 else 9000


def get_local_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]


async def main() -> None:
    ip   = get_local_ip()
    info = ServiceInfo(
        SERVICE_TYPE,
        f"{SERVICE_NAME}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=RC_PORT,
        properties={"version": "1.0", "proto": "udp"},
    )

    async with AsyncZeroconf() as azc:
        await azc.async_register_service(info)
        print(f"[mDNS] {SERVICE_NAME} @ {ip}:{RC_PORT}  (Ctrl+C to stop)")
        try:
            await asyncio.Future()
        except (asyncio.CancelledError, KeyboardInterrupt):
            pass
        await azc.async_unregister_service(info)

    print("[mDNS] unregistered")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
