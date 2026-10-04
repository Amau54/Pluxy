@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Build-Android.ps1" %*
set "PLUXY_EXIT=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %PLUXY_EXIT%
