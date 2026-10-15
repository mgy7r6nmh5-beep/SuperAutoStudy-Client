@echo off
REM SuperAutoStudy full-upgrade sentinel. Spawned by updater.py (detached).
REM Lives outside the install tree's python dir: cmd.exe is never replaced by
REM the installer, so this script always survives to relaunch the client.
REM Args: %1 = installer exe, %2 = setup log, %3 = launch.cmd, %4 = tmpdir
timeout /t 2 /nobreak >nul
"%~1" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /LOG=%~2
timeout /t 1 /nobreak >nul
if exist "%~3" call "%~3"
if exist "%~4" rmdir /s /q "%~4"
