@echo off
rem Launches the forge-llm tools on Windows:  scripts\llm <play|run|check> [options]
rem See llm.ps1 for what the modes do. (The real work is in PowerShell: the classpath is too long for cmd.)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0llm.ps1" %*
exit /b %ERRORLEVEL%
