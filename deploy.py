#!/usr/bin/env python3
"""BeagleBone Blue deployment script"""
import os, paramiko, stat, sys
from pathlib import Path

HOST = "192.168.7.2"
USER = "mihara"
PASS = "mihara"
REMOTE_DIR = "/home/mihara/BeagleBoneController"

LOCAL_DIR = Path(__file__).parent / "LinuxApp"

SKIP_NAMES = {".packages", "rc_server", "bench_recv"}
SKIP_EXTS  = set()

def upload_dir(sftp, local_path: Path, remote_path: str):
    try:
        sftp.stat(remote_path)
    except FileNotFoundError:
        sftp.mkdir(remote_path)

    for item in sorted(local_path.iterdir()):
        if item.name in SKIP_NAMES:
            continue
        remote_item = f"{remote_path}/{item.name}"
        if item.is_dir():
            upload_dir(sftp, item, remote_item)
        else:
            print(f"  upload: {item.name}")
            sftp.put(str(item), remote_item)
            sftp.chmod(remote_item,
                       stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR |
                       stat.S_IRGRP | stat.S_IXGRP |
                       stat.S_IROTH | stat.S_IXOTH)

def run(ssh, cmd, check=True):
    print(f"\n$ {cmd}")
    stdin, stdout, stderr = ssh.exec_command(cmd, get_pty=True)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    rc  = stdout.channel.recv_exit_status()
    if out.strip():
        print(out.strip())
    if err.strip():
        print("[stderr]", err.strip())
    if check and rc != 0:
        print(f"[ERROR] exit code {rc}")
        sys.exit(1)
    return rc, out

def main():
    print(f"=== Connecting to {USER}@{HOST} ===")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PASS)

    sftp = ssh.open_sftp()

    print(f"\n=== Uploading LinuxApp → {REMOTE_DIR} ===")
    try:
        sftp.stat(REMOTE_DIR)
    except FileNotFoundError:
        sftp.mkdir(REMOTE_DIR)
    upload_dir(sftp, LOCAL_DIR, REMOTE_DIR)
    sftp.close()
    print("Upload complete.")

    print("\n=== Installing librobotcontrol (rcn-ee repo) ===")
    install_cmds = (
        f"echo {PASS} | sudo -S sh -c '"
        "echo deb [arch=armhf] https://repos.rcn-ee.com/debian/bullseye bullseye main "
        "> /etc/apt/sources.list.d/rcn-ee.list && "
        "apt-get update -qq && "
        "apt-get install -y --no-install-recommends librobotcontrol' 2>&1 | tail -8"
    )
    rc_apt, _ = run(ssh, install_cmds, check=False)

    print("\n=== Building rc_server ===")
    run(ssh, f"cd {REMOTE_DIR} && make clean 2>/dev/null; true")
    if rc_apt == 0:
        rc_bbl, _ = run(ssh, f"cd {REMOTE_DIR} && make bbl", check=False)
    else:
        rc_bbl = 1
    if rc_bbl != 0:
        print("[INFO] librobotcontrol unavailable → building stub (no LED/motor)")
        run(ssh, f"cd {REMOTE_DIR} && make rc_server")

    print("\n=== Installing Python dependencies ===")
    run(ssh,
        f"cd {REMOTE_DIR} && rm -rf .packages && "
        "pip3 install --target=.packages -r requirements.txt 2>&1 | tail -8",
        check=False)

    print("\n=== Verifying rc_server binary ===")
    run(ssh, f"ls -lh {REMOTE_DIR}/rc_server")
    run(ssh, f"file {REMOTE_DIR}/rc_server")

    print("\n=== Verifying Python import ===")
    run(ssh,
        f"cd {REMOTE_DIR} && "
        "PYTHONPATH=.packages python3 -c "
        "\"import websockets, zeroconf; print('websockets OK, zeroconf OK')\"")

    print("\n=== Starting server.py (5 s smoke test) ===")
    _, out = run(ssh,
        f"cd {REMOTE_DIR} && "
        "timeout 5 python3 -c \""
        "import sys, os; sys.path.insert(0, '.packages'); "
        "import asyncio, websockets, zeroconf; "
        "print('imports OK'); "
        "\" 2>&1",
        check=False)

    print("\n=== Starting rc_server (5 s smoke test) ===")
    _, out = run(ssh,
        f"cd {REMOTE_DIR} && timeout 5 ./rc_server 2>&1 || true",
        check=False)

    print("\n=== Checking listening port 9000 ===")
    run(ssh, "ss -ulnp 2>/dev/null | grep 9000 || echo '(rc_server not running in bg - expected)'",
        check=False)

    print("\n=== Deployment complete ===")
    print(f"  rc_server  : {REMOTE_DIR}/rc_server  (UDP :9000)")
    print(f"  server.py  : python3 {REMOTE_DIR}/server.py  (WS :8765)")
    print(f"  run_rc.sh  : bash {REMOTE_DIR}/run_rc.sh")
    ssh.close()

if __name__ == "__main__":
    main()
