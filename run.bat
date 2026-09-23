@echo off
setlocal enabledelayedexpansion

echo ========================================
echo Network File Server (SSPOiRS)
echo ========================================

set APP_BIN=build\install\Network-File-Server-Lab\bin\Network-File-Server-Lab.bat

if not exist %APP_BIN% (
    echo [ERROR] Application not found. Please run BUILD_PROJECT.bat first.
    exit /b
)

if "%1"=="6" (
    echo Starting P2P Chat...
    echo If auto-detection fails, you can specify IP manually:
    echo run.bat 6 192.168.1.100
    echo.
)

call %APP_BIN% %*
