@echo off
setlocal enabledelayedexpansion
title Pluxy Server
chcp 65001 >nul 2>nul
cd /d "%~dp0server"

echo ============================================
echo            PLUXY  -  Serveur media
echo ============================================
echo.

set "VENV_PY=.venv\Scripts\python.exe"
set "VENV_PYW=.venv\Scripts\pythonw.exe"

rem --- Si l'environnement existe deja : on l'utilise directement ---------
rem     (chemin absolu -> ne depend pas du PATH systeme)
if exist "%VENV_PY%" goto :run

rem --- Premiere execution : il faut un Python systeme pour creer le venv -
echo [Pluxy] Premiere installation : recherche de Python...
set "SYS_PY="
where.exe py.exe >nul 2>nul
if not errorlevel 1 set "SYS_PY=py -3"
if defined SYS_PY goto :python_found
where.exe python.exe >nul 2>nul
if not errorlevel 1 set "SYS_PY=python"
if defined SYS_PY goto :python_found
if "%SYS_PY%"=="" (
  echo [ERREUR] Python introuvable. Installez Python 3.11+ ^(cochez "Add to PATH"^)
  echo          depuis https://python.org puis relancez ce fichier.
  pause
  exit /b 1
)

:python_found
echo [Pluxy] Creation de l'environnement avec "%SYS_PY%"...
%SYS_PY% -m venv .venv
if not exist "%VENV_PY%" ( echo [ERREUR] Echec creation venv & pause & exit /b 1 )

echo [Pluxy] Installation des dependances ^(une seule fois^)...
"%VENV_PY%" -m pip install --upgrade pip
"%VENV_PY%" -m pip install -r requirements.txt
if errorlevel 1 ( echo [ERREUR] Echec installation dependances & pause & exit /b 1 )

:run
rem --- Ouvre le navigateur sur l'UI une fois le serveur pret ------------
start "" cmd /c "timeout /t 4 >nul & start http://localhost:8420"

echo [Pluxy] Ouverture de l'application…
echo [Pluxy] Une icone apparait dans la zone de notification Windows.
echo [Pluxy] Utilisez-la pour ouvrir, arreter ou redemarrer le serveur.
if exist "%VENV_PYW%" (
  start "" "%VENV_PYW%" pluxy_tray.pyw
) else (
  start "" "%VENV_PY%" pluxy_tray.pyw
)
exit /b 0
