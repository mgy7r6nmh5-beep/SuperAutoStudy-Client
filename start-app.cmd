@echo off
REM ============================================================
REM  Start SuperAutoStudy backend  (http://localhost:3030)
REM  Prerequisites: MySQL running + jar already built
REM
REM  Idempotent: if port 3030 is already listening, this script
REM  reports it and exits instead of failing with a bind error.
REM ============================================================
setlocal enabledelayedexpansion
set ROOT=%~dp0
set JAVA_HOME=%ROOT%jdk8
set JAVA=%JAVA_HOME%\bin\java.exe

if not exist "%JAVA%" (
  echo [ERROR] JDK 8 not found: %JAVA%
  pause
  exit /b 1
)

REM --- already running? ---
netstat -ano | findstr ":3030" | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [SKIP] Port 3030 is already listening.
  echo        The backend is already running - just open:
  echo            http://localhost:3030/
  echo        To restart it, close the existing window first.
  echo.
  pause
  exit /b 0
)

set JAR=
for %%f in ("%ROOT%app\target\SuperAuto-*.jar") do set JAR=%%f
if "!JAR!"=="" (
  echo [ERROR] jar not built yet. Run build.cmd first.
  pause
  exit /b 1
)

echo Starting SuperAutoStudy ...
echo   JAVA_HOME = %JAVA_HOME%
echo   jar       = !JAR!
echo   port      = 3030
echo   stop      = press Ctrl+C in this window
echo.

REM --server.port is passed explicitly on purpose: some environments
REM inject SERVER__PORT (double underscore), which Spring Boot relaxed
REM binding reads as server.port and would override application.yml.
REM Command line arguments win over environment variables.
"!JAVA!" -Dfile.encoding=UTF-8 -jar "!JAR!" --server.port=3030
echo.
echo Application has exited.
pause
endlocal
