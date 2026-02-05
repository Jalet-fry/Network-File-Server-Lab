@echo off
set APP_BIN=build\install\Network-File-Server-Lab\bin\Network-File-Server-Lab.bat
if not exist %APP_BIN% (
    echo [ERROR] Application not found. Please run BUILD_PROJECT.bat first.
    exit /b
)
call %APP_BIN% %*
