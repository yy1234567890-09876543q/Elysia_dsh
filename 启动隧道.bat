@echo off
cd /d "%~dp0"
echo ========================================
echo   DShChat Tunnel
echo   Local: http://127.0.0.1:3080
echo ========================================
echo.
echo Copy the https URL from "Forwarding" line below,
echo paste it into the app settings.
echo.
ngrok.exe http 3080 --host-header=rewrite --config "%~dp0ngrok.yml"
pause