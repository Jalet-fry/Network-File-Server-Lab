@echo off
echo Launching 2 P2P Chat instances...
start "Chat Window 1" cmd /k run.bat 6
timeout /t 2 >nul
start "Chat Window 2" cmd /k run.bat 6