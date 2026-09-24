#!/bin/bash
[ "$EUID" -ne 0 ] && exec sudo bash "$0" "$@"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mapfile -t RAW_HOSTS < <(grep -v '^[[:space:]]*$' "$SCRIPT_DIR/hosts.txt")
WIN_IP=$(echo "${RAW_HOSTS[0]}" | tr -cd '0-9.')
FEDORA_IP=$(echo "${RAW_HOSTS[1]}" | tr -cd '0-9.')

echo "=== ДЕМОНСТРАЦИЯ ЛР 5: СЫРЫЕ СОКЕТЫ И ПОДМЕНА IP ==="
echo "[1/2] Smurf-механика: подмена Source на Windows ($WIN_IP) -> отправка на Fedora ($FEDORA_IP)..."
"$SCRIPT_DIR/run.sh" 5 raw "$WIN_IP" "$FEDORA_IP" 10
echo ""
echo "[2/2] Прямая подмена IP: пакеты с фейковым Source IP 1.1.1.1 напрямую в Windows ($WIN_IP)..."
"$SCRIPT_DIR/run.sh" 5 raw "1.1.1.1" "$WIN_IP" 5
echo "Готово! Смотрите пакеты в Wireshark на Windows."