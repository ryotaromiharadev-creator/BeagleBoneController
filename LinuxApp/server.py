#!/usr/bin/env python3
"""
LinuxConnect Server
WiFiネットワーク内のAndroidデバイスとリアルタイム通信するWebSocketサーバ
"""
import sys, os, signal
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".packages"))

import asyncio
import json
import socket
import time
from websockets.asyncio.server import serve, ServerConnection
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

SERVICE_TYPE = "_linuxconnect._tcp.local."
SERVICE_NAME = "LinuxConnect"
PORT = 8765

connected_clients: set[ServerConnection] = set()


async def handle_client(websocket: ServerConnection):
    addr = websocket.remote_address
    print(f"\r[+] Android接続: {addr[0]}:{addr[1]}", flush=True)
    connected_clients.add(websocket)
    try:
        async for raw in websocket:
            data = json.loads(raw)
            msg_type = data.get("type")
            if msg_type == "ping":
                await websocket.send(json.dumps({"type": "pong", "timestamp": time.time()}))
            elif msg_type == "message":
                content = data.get("content", "")
                print(f"\r[Android] {content}\n> ", end="", flush=True)
    except Exception:
        pass
    finally:
        connected_clients.discard(websocket)
        print(f"\r[-] Android切断: {addr[0]}:{addr[1]}", flush=True)


async def broadcast(content: str):
    if not connected_clients:
        print("[!] 接続中のクライアントがいません", flush=True)
        return
    payload = json.dumps({"type": "message", "content": content, "timestamp": time.time()})
    results = await asyncio.gather(
        *(c.send(payload) for c in connected_clients),
        return_exceptions=True,
    )
    ok = sum(1 for r in results if not isinstance(r, Exception))
    print(f"[送信] {ok}/{len(results)} クライアントに送信", flush=True)


def get_local_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]


async def stdin_reader():
    loop = asyncio.get_event_loop()
    print("> ", end="", flush=True)
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        line = line.strip()
        if line:
            await broadcast(line)
            print("> ", end="", flush=True)


async def main():
    ip = get_local_ip()
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()

    loop.add_signal_handler(signal.SIGINT,  stop.set)
    loop.add_signal_handler(signal.SIGTERM, stop.set)

    info = ServiceInfo(
        SERVICE_TYPE,
        f"{SERVICE_NAME}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton(ip)],
        port=PORT,
        properties={"version": "1.0"},
    )

    async with AsyncZeroconf() as azc:
        await azc.async_register_service(info)

        async with serve(handle_client, "0.0.0.0", PORT):
            print("=" * 50)
            print("LinuxConnect Server")
            print("=" * 50)
            print(f"  IPアドレス : {ip}")
            print(f"  ポート     : {PORT}")
            print(f"  mDNS       : {SERVICE_NAME} ({SERVICE_TYPE[:-1]})")
            print("  Androidアプリを起動してこのサーバを選択してください")
            print("  メッセージを入力してEnterで全クライアントに送信します")
            print("  終了: Ctrl+C")
            print("=" * 50)

            stdin_task = asyncio.create_task(stdin_reader())
            await stop.wait()
            # stdin スレッドをアンブロックしてからキャンセル
            try:
                sys.stdin.close()
            except Exception:
                pass
            stdin_task.cancel()
            try:
                await stdin_task
            except (asyncio.CancelledError, Exception):
                pass

        await azc.async_unregister_service(info)

    print("\nサーバを停止しました")


if __name__ == "__main__":
    asyncio.run(main())
    os._exit(0)  # run_in_executor スレッドが残っても強制終了
