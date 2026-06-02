@echo off
setlocal enabledelayedexpansion
title Pluxy - Installation (TV / mobile)
chcp 65001 >nul
cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" ( echo [ERREUR] adb introuvable (%ADB%) & pause & exit /b 1 )

rem --- Prend l'APK la plus recente de dist\ ------------------------------
set "APK="
for /f "delims=" %%F in ('dir /b /o-d "dist\*.apk" 2^>nul') do if not defined APK set "APK=dist\%%F"
if not defined APK ( echo [ERREUR] Aucune APK dans dist\. Lancez Build-APK.bat d'abord. & pause & exit /b 1 )

echo ============================================
echo     PLUXY  -  Installation via ADB (Wi-Fi)
echo ============================================
echo APK : %APK%
echo.
echo Prerequis sur l'appareil (TV Philips 803 OU telephone) :
echo   Parametres ^> Systeme ^> A propos ^> cliquer 7x sur "Numero de build"
echo   puis Options developpeur ^> activer "Debogage sans fil" / "Debogage reseau"
echo.
set /p TVIP=Adresse IP de l'appareil (ex 192.168.1.50) :

echo [Pluxy] Connexion a %TVIP%:5555 ...
"%ADB%" connect %TVIP%:5555
if errorlevel 1 ( echo [ERREUR] Connexion ADB echouee & pause & exit /b 1 )

echo [Pluxy] Installation / mise a jour de l'APK...
rem  -r : reinstalle par-dessus (conserve les donnees, meme signature stable)
"%ADB%" -s %TVIP%:5555 install -r "%APK%"

echo.
echo [Pluxy] Termine. L'app "Pluxy" apparait dans le lanceur (TV et mobile).
echo         Au 1er lancement, le serveur est detecte automatiquement.
pause
