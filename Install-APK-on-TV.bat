@echo off
setlocal
title Pluxy - Installation sur Android TV
chcp 65001 >nul
cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" ( echo [ERREUR] adb introuvable (%ADB%) & pause & exit /b 1 )
if not exist "dist\Pluxy-TV-1.0.0.apk" ( echo [ERREUR] APK absent. Lancez Build-APK.bat d'abord. & pause & exit /b 1 )

echo ============================================
echo     PLUXY  -  Installation sur la TV (ADB)
echo ============================================
echo.
echo Prerequis sur la Philips 803 :
echo   Parametres ^> Systeme ^> A propos ^> cliquer 7x sur "Build"
echo   puis Parametres ^> Prefs. dev ^> "Debogage USB" + "Debogage reseau" ON
echo.
set /p TVIP=Adresse IP de la TV (ex 192.168.1.50) :

echo [Pluxy] Connexion a %TVIP%:5555 ...
"%ADB%" connect %TVIP%:5555
if errorlevel 1 ( echo [ERREUR] Connexion ADB echouee & pause & exit /b 1 )

echo [Pluxy] Installation de l'APK...
"%ADB%" -s %TVIP%:5555 install -r "dist\Pluxy-TV-1.0.0.apk"

echo.
echo [Pluxy] Termine. L'app "Pluxy" apparait dans le lanceur Android TV.
echo         Pensez a regler l'IP du serveur au 1er lancement.
pause
