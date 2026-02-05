@echo off
echo Building project and preparing installation folders...
call gradlew.bat installDist
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Build failed! Check the errors above.
    pause
) else (
    echo.
    echo [SUCCESS] Build complete. Now you can use START scripts.
    pause
)
