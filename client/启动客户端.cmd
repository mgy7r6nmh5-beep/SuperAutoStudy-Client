@echo off
REM ============================================================
REM  SuperAutoStudy Client - dev launcher
REM  Starts the local service and opens the native window.
REM
REM  Python resolution order:
REM    1) portable runtime in ..\python\   (portable package)
REM    2) pythonw.exe from PATH
REM ============================================================
setlocal
set "PY=%~dp0..\python\pythonw.exe"
if not exist "%PY%" set "PY=%~dp0..\python\python.exe"
if not exist "%PY%" set "PY=pythonw.exe"
start "" "%PY%" "%~dp0desktop.py"
endlocal
