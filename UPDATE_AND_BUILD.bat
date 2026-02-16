@echo off
echo === Windows Auto Update ^& Build ===

rem 1. Тянем изменения из репозитория
echo [1/2] Pulling latest changes from Git...
git pull

rem 2. Запускаем сборку
echo [2/2] Starting build process...
call BUILD_PROJECT.bat

echo ==================================
echo DONE! You can now start the server with: run.bat
pause
