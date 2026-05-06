@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0android_preview.ps1" %*
