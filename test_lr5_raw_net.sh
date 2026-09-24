#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOSTS_FILE="$SCRIPT_DIR/hosts.txt"

if [ ! -f "$HOSTS_FILE" ]; then
    echo "hosts.txt not found!"
    exit 1
fi

mapfile -t HOSTS < <(grep -v '^[[:space:]]*$' "$HOSTS_FILE")
WIN_IP="${HOSTS[0]}"
FEDORA_IP="${HOSTS[1]}"

# Автоматически вычисляем Broadcast из адреса (замена последнего октета на 255)
BROADCAST_IP=$(echo "$WIN_IP" | sed 's/\.[0-9]*$/.255/')

echo "Sending raw ICMP packets: Source=$WIN_IP -> Broadcast=$BROADCAST_IP..."
"$SCRIPT_DIR/run.sh" 5 raw "$WIN_IP" "$BROADCAST_IP" 10