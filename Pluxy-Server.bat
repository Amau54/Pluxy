@echo off
setlocal
title Pluxy Server
chcp 65001 >nul 2>nul
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Start-PC.ps1" %*
set "PLUXY_EXIT=%ERRORLEVEL%"
if not "%PLUXY_EXIT%"=="0" pause
exit /b %PLUXY_EXIT%
