@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0he-server.ps1" %*
