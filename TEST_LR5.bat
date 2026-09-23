@echo off
net session >nul 2>&1
if %errorLevel% NEQ 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

:: Автоматически отключаем блокировку в Windows
netsh advfirewall set currentprofile state off >nul 2>&1

echo ===================================================
echo [1/2] TEST PARALLEL PING
echo ===================================================
call run.bat 5 ping 8.8.8.8 10.220.155.244
echo.
pause

echo ===================================================
echo [2/2] TEST SMURF SPOOFING
echo ===================================================
call run.bat 5 smurf 10.220.155.244 10.220.155.255 10
echo.
pause