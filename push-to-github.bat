@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==========================================================
echo   StarFox - push to your GitHub and build the APK
echo ==========================================================
echo.
echo   Need a GitHub token first:
echo     https://github.com/settings/tokens
echo   scopes:  repo  +  workflow
echo.
echo   Repo format:  yourname/yourrepo
echo ----------------------------------------------------------
echo.

set /p REPO=Repo  (owner/name) :
set /p TOKEN=Token (hidden-ish)       :

if "%REPO%"=="" goto :empty
if "%TOKEN%"=="" goto :empty

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-to-github.ps1" -Repo "%REPO%" -Token "%TOKEN%" -Build

echo.
echo [done] check _download folder for the APK.
pause
exit /b 0

:empty
echo.
echo [!] repo or token is empty, aborted.
pause
exit /b 1
