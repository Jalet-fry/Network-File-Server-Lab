#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mapfile -t RAW_HOSTS < <(grep -v '^[[:space:]]*$' "$SCRIPT_DIR/hosts.txt")
WIN_IP=$(echo "${RAW_HOSTS[0]}" | tr -cd '0-9.')
FEDORA_IP=$(echo "${RAW_HOSTS[1]}" | tr -cd '0-9.')

echo "=== [ЛР 7 Worker] Ожидание 2 тестов (Non-blocking и Blocking)... ==="
echo "[1/2] Запуск для Non-blocking теста..."
"$SCRIPT_DIR/run.sh" 7 rank=1 hosts="$WIN_IP,$FEDORA_IP"

echo ""
echo "[2/2] Запуск для Blocking теста..."
"$SCRIPT_DIR/run.sh" 7 rank=1 hosts="$WIN_IP,$FEDORA_IP"
echo "Оба теста завершены успешно!"