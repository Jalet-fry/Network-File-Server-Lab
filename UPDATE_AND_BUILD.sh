#!/bin/bash

echo "=== Linux / Fedora / Termux Hard Update & Build ==="

# 1. Жестко сбрасываем состояние к последнему коммиту из origin/main
echo "[1/4] Fetching and hard-resetting to origin/main..."
git fetch origin main
git reset --hard origin/main
# Удаляем все неотслеживаемые старые файлы и мусор
git clean -fd

# 2. Исправляем права доступа на скрипты
echo "[2/4] Setting execution permissions..."
chmod +x *.sh gradlew 2>/dev/null

# 3. Запускаем сборку
echo "[3/4] Starting build process..."
./BUILD_PROJECT.sh

# 4. Базовая системная подготовка (права)
echo "[4/4] Finalizing setup..."
chmod +x build/install/*/bin/* 2>/dev/null

echo "=================================="
echo "DONE! Репозиторий на 100% синхронизирован с GitHub!"