#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d ".packages" ]; then
    echo "依存ライブラリをインストール中..."
    pip install --target=.packages -r requirements.txt
fi

python3 server.py
