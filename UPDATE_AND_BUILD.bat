@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo === Windows Hard Update ^& Build ===

echo [1/3] Жесткий сброс к origin/main...
git fetch origin main
git reset --hard origin/main
git clean -fd

echo [2/3] Запуск сборки проекта...
call BUILD_PROJECT.bat

echo ==================================
echo ГОТОВО! Репозиторий полностью синхронизирован.
pause