@echo off
:: Проверка прав администратора
net session >nul 2>&1
if %errorLevel% == 0 (
    echo [OK] Running as Administrator.
) else (
    echo [ERROR] Please right-click this file and select 'Run as Administrator'.
    pause
    exit /b
)

echo === Network File Server Firewall Fix ===

set PORT=8888

echo [1/4] Deleting old firewall rules for port %PORT%...
netsh advfirewall firewall delete rule name="Lab_Server_TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="Lab_Server_UDP" >nul 2>&1

echo [2/4] Adding TCP Rule (Port %PORT%)...
netsh advfirewall firewall add rule name="Lab_Server_TCP" dir=in action=allow protocol=TCP localport=%PORT% profile=any

echo [3/4] Adding UDP Rule (Port %PORT%)...
netsh advfirewall firewall add rule name="Lab_Server_UDP" dir=in action=allow protocol=UDP localport=%PORT% profile=any

echo [4/4] Ensuring network discovery is on...
netsh advfirewall firewall set rule group="Network Discovery" new enable=Yes >nul 2>&1

echo ========================================
echo [SUCCESS] Firewall rules updated for port %PORT%
echo Now your phone should be able to connect!
echo ========================================
pause
