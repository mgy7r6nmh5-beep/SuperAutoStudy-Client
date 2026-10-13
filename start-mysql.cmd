@echo off
REM ============================================================
REM  Start local MySQL 5.7 for SuperAutoStudy
REM  MySQL runs in THIS window. Close the window to stop MySQL.
REM
REM  Idempotent: if port 3306 is already listening, this script
REM  reports it and exits instead of failing to bind.
REM ============================================================
setlocal
set ROOT=%~dp0
set MYSQLD=%ROOT%mysql57\bin\mysqld.exe
set MYINI=%ROOT%my.ini

if not exist "%MYSQLD%" (
  echo [ERROR] mysqld.exe not found:
  echo         %MYSQLD%
  echo Run the extraction step first.
  pause
  exit /b 1
)

REM --- already running? ---
netstat -ano | findstr ":3306" | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [SKIP] Port 3306 is already listening.
  echo        MySQL seems to be running already.
  echo        Close the other MySQL window first if you want to restart it.
  echo.
  pause
  exit /b 0
)

echo Starting MySQL 5.7 ...
echo   port = 3306
echo   data = %ROOT%mysql57\data
echo   log  = %ROOT%mysql57\mysql-error.log
echo.
echo Close this window to stop MySQL.
echo.

"%MYSQLD%" --defaults-file="%MYINI%" --console
echo.
echo MySQL has exited.
pause
endlocal
