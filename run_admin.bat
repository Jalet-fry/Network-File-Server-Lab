@echo off
:: Проверка прав администратора
net session >nul 2>&1
if %errorLevel% == 0 (
    echo [ADMIN] Running with elevated privileges...
    call run.bat %*
) else (
    echo [ADMIN] Requesting elevation...
    powershell -Command "Start-Process '%~f0' -ArgumentList '%*' -Verb RunAs"
    exit /b
)
