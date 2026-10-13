@echo off
cd /d "%~dp0"
"%~dp0jdk8\bin\java.exe" -Dfile.encoding=UTF-8 -jar "%~dp0app\target\SuperAuto-1.0-SNAPSHOT.jar" --server.port=3030 > "%~dp0app\live.log" 2>&1
