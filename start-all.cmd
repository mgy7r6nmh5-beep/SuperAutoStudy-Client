@echo off
REM ============================================================
REM  One-click start: MySQL in a new window, then the backend
REM ============================================================
setlocal
set ROOT=%~dp0

echo Launching MySQL in a separate window ...
start "SuperAutoStudy MySQL" cmd /k "%ROOT%start-mysql.cmd"

echo Waiting 10s for MySQL to come up ...
timeout /t 10 /nobreak >nul

call "%ROOT%start-app.cmd"
endlocal
