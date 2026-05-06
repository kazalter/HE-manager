@echo off
setlocal enabledelayedexpansion
title HE Manager Mobile Service

set "APP_ROOT=%~dp0"
set "BACKEND_DIR=%APP_ROOT%backend"
set "FRONTEND_DIR=%APP_ROOT%frontend"

echo ==========================================
echo   HE Manager Mobile Service
echo ==========================================
echo.
echo Backend API will listen on:
echo   http://0.0.0.0:8010
echo.
echo On your phone, use:
echo   http://YOUR_PC_LAN_IP:8010
echo.

echo Cleaning up previous backend/frontend processes...
powershell -NoProfile -ExecutionPolicy Bypass -Command "8010,5173 | ForEach-Object { Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue } | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Write-Host ('Closing PID ' + $_ + '...'); Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }"

echo.
echo [1/3] Starting Backend Service...
start "HE_Mobile_Service" /D "%BACKEND_DIR%" cmd /k python -m uvicorn app.main:app --app-dir "%BACKEND_DIR%" --host 0.0.0.0 --port 8010 --no-access-log

echo [2/3] Starting Frontend UI...
start "HE_Frontend_UI" /D "%FRONTEND_DIR%" cmd /k npm run dev -- --host

echo [3/3] Waiting for services to be ready...
echo (This may take a moment on first start)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(45); do { $c = New-Object System.Net.Sockets.TcpClient; $t = $c.BeginConnect('127.0.0.1', 8010, $null, $null); if ($t.AsyncWaitHandle.WaitOne(500)) { $c.EndConnect($t); $c.Close(); exit 0 }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline); exit 1"
if errorlevel 1 (
    echo [!] Backend check timed out, but proceeding anyway...
) else (
    echo [+] Backend is ready.
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddSeconds(45); do { $c = New-Object System.Net.Sockets.TcpClient; $t = $c.BeginConnect('127.0.0.1', 5173, $null, $null); if ($t.AsyncWaitHandle.WaitOne(500)) { $c.EndConnect($t); $c.Close(); exit 0 }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline); exit 1"
if errorlevel 1 (
    echo [!] Frontend check timed out, but proceeding anyway...
) else (
    echo [+] Frontend is ready.
)

echo.
echo Opening browser...
start http://localhost:5173

echo.
echo ==========================================
echo   HE Manager is now running!
echo.
echo   Local:   http://localhost:5173
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr "IPv4" ^| findstr /V "127.0.0.1"') do (
    set "IP=%%a"
    set "IP=!IP: =!"
    echo   Network: http://!IP!:5173
)
echo.
echo   Service and UI are running. Keep the service windows open.
echo ==========================================
pause
