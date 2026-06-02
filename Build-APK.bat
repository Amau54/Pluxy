@echo off
setlocal enabledelayedexpansion
title Pluxy - Build APK
chcp 65001 >nul
cd /d "%~dp0client"

echo ============================================
echo        PLUXY  -  Build APK Android TV
echo ============================================
echo.

rem --- Localise un JDK 17+ (Temurin/Adoptium ou JAVA_HOME) ---------------
if defined JAVA_HOME (
  set "JDK=%JAVA_HOME%"
) else (
  for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do set "JDK=%%D"
)
if not defined JDK (
  echo [ERREUR] Aucun JDK 17+ trouve. Installez Temurin 17 ou 21.
  pause & exit /b 1
)
set "JAVA_HOME=%JDK%"
echo [Pluxy] JAVA_HOME = %JAVA_HOME%

rem --- local.properties (chemin SDK) si absent ---------------------------
if not exist "local.properties" (
  if exist "%LOCALAPPDATA%\Android\Sdk" (
    > local.properties echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk
  ) else (
    echo [ERREUR] SDK Android introuvable. Definissez sdk.dir dans client\local.properties
    pause & exit /b 1
  )
)

echo [Pluxy] Compilation de l'APK debug...
call gradlew.bat assembleDebug --console=plain --warning-mode=none
if errorlevel 1 ( echo [ERREUR] Build echoue & pause & exit /b 1 )

if not exist "..\dist" mkdir "..\dist"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "..\dist\Pluxy-TV-1.0.0.apk" >nul

rem --- Politique de retention : on ne garde que les 3 APK les plus recents -
echo [Pluxy] Nettoyage : conservation des 3 dernieres versions...
set /a n=0
for /f "delims=" %%F in ('dir /b /o-d "..\dist\*.apk" 2^>nul') do (
  set /a n+=1
  if !n! gtr 3 ( echo   - suppression %%F & del "..\dist\%%F" )
)

echo.
echo [Pluxy] APK genere : dist\Pluxy-TV-1.0.0.apk
pause
