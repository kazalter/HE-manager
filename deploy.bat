@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo             HE Manager Linux Deployment
echo ===================================================
echo.

:: Detect Python interpreter with proper fallback
set PYTHON_BIN=python
if exist "C:\Users\25768\AppData\Local\Programs\Python\Python312\python.exe" (
    set PYTHON_BIN="C:\Users\25768\AppData\Local\Programs\Python\Python312\python.exe"
)

echo Using Python: !PYTHON_BIN!
echo.

!PYTHON_BIN! "%~dp0deploy_to_linux.py"
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Deployment failed with exit code %errorlevel%.
    echo Please make sure the required packages (like paramiko) are installed.
    echo You can install them with: !PYTHON_BIN! -m pip install paramiko
) else (
    echo.
    echo [SUCCESS] Deployment finished successfully!
)

echo.
echo ===================================================
echo Press any key to exit...
pause > nul
