@echo off
net session >nul 2>&1
if %errorLevel% NEQ 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo ===================================================
echo [1/3] TEST PARALLEL PING (8.8.8.8, 1.1.1.1, 77.88.8.8)
echo ===================================================
call run.bat 5 ping 8.8.8.8 1.1.1.1 77.88.8.8
echo.
pause

echo ===================================================
echo [2/3] TEST TRACEROUTE (8.8.8.8)
echo ===================================================
call run.bat 5 trace 8.8.8.8
echo.
pause

echo ===================================================
echo [3/3] TEST SMURF SPOOFING (10 packets)
echo ===================================================
call run.bat 5 smurf 192.168.1.150 192.168.1.255 10
echo.
echo All LR5 tests completed!
pause