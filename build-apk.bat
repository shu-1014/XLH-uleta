@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==========================================
echo   StarFox  -  one-click APK build
echo ==========================================
echo.

where powershell >nul 2>nul
if errorlevel 1 (
    echo [!] powershell not found
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
if errorlevel 1 (
    echo.
    echo [!] BUILD FAILED - check the red text above.
) else (
    echo.
    echo [OK] done.
)
pause
