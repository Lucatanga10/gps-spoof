@echo off
REM GEN-KEYSTORE.bat — genera la debug.keystore fissa una volta sola.
REM Doppio click. Poi carica app\debug.keystore su GitHub insieme agli altri file.

setlocal
cd /d "%~dp0"

set KEYTOOL=
if exist "C:\revtools\jdk\bin\keytool.exe" set KEYTOOL=C:\revtools\jdk\bin\keytool.exe

if not defined KEYTOOL (
    where keytool >nul 2>&1
    if not errorlevel 1 set KEYTOOL=keytool
)

if not defined KEYTOOL (
    echo [ERRORE] keytool non trovato. Installa JDK o metti Java nel PATH.
    pause
    exit /b 1
)

if exist "app\debug.keystore" (
    echo Keystore gia esistente in app\debug.keystore.
    echo Se vuoi rigenerarla, cancellala prima.
    pause
    exit /b 0
)

echo Genero app\debug.keystore ...
"%KEYTOOL%" -genkey -v -keystore "app\debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

if not exist "app\debug.keystore" (
    echo [ERRORE] generazione fallita.
    pause
    exit /b 1
)

echo.
echo ============================================
echo [+] app\debug.keystore generata.
echo [+] Carica su GitHub questa e i file modificati:
echo       - app\debug.keystore
echo       - app\build.gradle
echo       - .github\workflows\build.yml
echo [+] Da qui in poi tutte le build hanno la stessa firma.
echo ============================================
pause
