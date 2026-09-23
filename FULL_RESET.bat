@echo off
echo [1/5] Killing all Java processes to release Gradle locks...
taskkill /F /IM java.exe /T >nul 2>&1

echo [2/5] Cleaning Gradle caches and lock files...
if exist "%USERPROFILE%\.gradle\caches\journal-1\journal-1.lock" (
    del /F /Q "%USERPROFILE%\.gradle\caches\journal-1\journal-1.lock"
)

echo [3/5] Deleting project temporary folders (build, .gradle)...
rd /s /q .gradle >nul 2>&1
rd /s /q build >nul 2>&1

echo [4/5] Building project from scratch...
call gradlew.bat installDist --no-daemon

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed! Check the errors above.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [5/5] SUCCESS! Project is rebuilt.
pause