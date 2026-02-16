#!/bin/bash

echo "=== Termux Auto Update & Build ==="

# 1. Тянем изменения из репозитория
echo "[1/3] Pulling latest changes from Git..."
git pull

# 2. Исправляем права доступа (важно для Termux/Linux)
echo "[2/3] Setting execution permissions..."
chmod +x BUILD_PROJECT.sh
chmod +x run.sh
chmod +x gradlew

# 3. Запускаем сборку
echo "[3/3] Starting build process..."
./BUILD_PROJECT.sh

echo "=================================="
echo "DONE! You can now start the server with: ./run.sh"
