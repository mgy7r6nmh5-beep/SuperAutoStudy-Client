@echo off
REM ============================================================
REM  Rebuild SuperAutoStudy jar with JDK 8 + Maven
REM  Uses the bundled JDK8 so your system JDK 27 is untouched.
REM ============================================================
setlocal
set ROOT=%~dp0
set JAVA_HOME=%ROOT%jdk8
set MVN=%ROOT%maven\bin\mvn.cmd
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "%MVN%" (
  echo [ERROR] maven not found: %MVN%
  pause
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 8 not found: %JAVA_HOME%
  pause
  exit /b 1
)

cd /d "%ROOT%app"
echo Building with:
"%JAVA_HOME%\bin\java.exe" -version
echo.
call "%MVN%" -s "%ROOT%settings.xml" clean package -DskipTests
echo.
echo Build finished. Jar is in %ROOT%app\target\
pause
endlocal
