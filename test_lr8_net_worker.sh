#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mapfile -t RAW_HOSTS < <(grep -v '^[[:space:]]*$' "$SCRIPT_DIR/hosts.txt")
WIN_IP=$(echo "${RAW_HOSTS[0]}" | tr -cd '0-9.')
FEDORA_IP=$(echo "${RAW_HOSTS[1]}" | tr -cd '0-9.')

echo "Ожидание вычислений групп от Master (Windows: $WIN_IP)..."
"$SCRIPT_DIR/run.sh" 8 rank=1 hosts="$WIN_IP,$FEDORA_IP" groups=1 size=600