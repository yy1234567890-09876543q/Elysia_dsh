@echo off
cd /d "%~dp0"
echo ========================================
echo   Config ngrok authtoken
echo ========================================
echo.
echo 1. Open https://dashboard.ngrok.com/get-started/your-authtoken
echo 2. Copy your token
echo 3. Right-click in this window to paste it, then press Enter
echo.
set /p TOKEN="Paste your authtoken: "

if "%TOKEN%"=="" (
    echo No input, exit.
    pause
    exit /b
)

(
echo version: "3"
echo agent:
echo     authtoken: %TOKEN%
) > ngrok.yml

echo.
echo ========================================
echo   Done! ngrok.yml saved.
echo   Next: double-click the tunnel .bat in this folder.
echo ========================================
pause