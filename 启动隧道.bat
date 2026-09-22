@echo off
cd /d "%~dp0"
echo ========================================
echo   Elysia_dsh Tunnel
echo   Local: http://127.0.0.1:3080
echo ========================================
echo.
echo Copy the https:// address on the "Forwarding" line below,
echo then paste it into the app settings (DSH server address).
echo.
echo Keep this window OPEN while you use the app!
echo.
ngrok.exe http 3080 --host-header=rewrite --config "%~dp0ngrok.yml"
pause