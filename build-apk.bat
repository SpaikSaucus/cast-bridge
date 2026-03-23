@echo off
setlocal enabledelayedexpansion

echo ============================================
echo  CastBridge - Building APK via Docker
echo ============================================
echo.

REM Resolve the full path of the batch file's directory
set "PROJECT_DIR=%~dp0"
if "!PROJECT_DIR:~-1!"=="\" set "PROJECT_DIR=!PROJECT_DIR:~0,-1!"

REM Read .env file
if not exist "!PROJECT_DIR!\.env" (
    echo ERROR: .env file not found.
    echo.
    echo Create it by copying the example:
    echo   copy .env.example .env
    echo.
    echo Then edit .env and set your passwords.
    pause
    exit /b 1
)
for /f "usebackq tokens=1,2 delims==" %%a in ("!PROJECT_DIR!\.env") do (
    set "%%a=%%b"
)

REM Create output directory
if not exist "!PROJECT_DIR!\app-output" mkdir "!PROJECT_DIR!\app-output"

REM Write secrets to temporary files for Docker BuildKit
set "SECRETS_DIR=%TEMP%\castbridge-secrets-%RANDOM%"
mkdir "!SECRETS_DIR!"
echo|set /p="!KEYSTORE_PASSWORD!" > "!SECRETS_DIR!\KEYSTORE_PASSWORD"
echo|set /p="!KEY_ALIAS!" > "!SECRETS_DIR!\KEY_ALIAS"
echo|set /p="!KEY_PASSWORD!" > "!SECRETS_DIR!\KEY_PASSWORD"

echo [1/2] Building Docker image (this may take a few minutes the first time)...
set "DOCKER_BUILDKIT=1"
call docker build ^
    --secret id=KEYSTORE_PASSWORD,src="!SECRETS_DIR!\KEYSTORE_PASSWORD" ^
    --secret id=KEY_ALIAS,src="!SECRETS_DIR!\KEY_ALIAS" ^
    --secret id=KEY_PASSWORD,src="!SECRETS_DIR!\KEY_PASSWORD" ^
    -t castbridge-builder "!PROJECT_DIR!"
set "BUILD_RESULT=!ERRORLEVEL!"

REM Clean up secret files
rmdir /s /q "!SECRETS_DIR!" 2>nul

if !BUILD_RESULT! NEQ 0 (
    echo.
    echo ERROR: Docker build failed.
    echo.
    echo Possible causes:
    echo  - Docker Desktop is not running
    echo  - Not enough disk space [need ~4 GB free]
    echo  - No internet connection
    pause
    exit /b 1
)

echo.
echo [2/2] Extracting APK...
call docker run --rm -v "!PROJECT_DIR!\app-output:/output" castbridge-builder
if !ERRORLEVEL! NEQ 0 (
    echo.
    echo ERROR: Failed to extract APK.
    pause
    exit /b 1
)

echo.
echo ============================================
echo  SUCCESS! APK generated at:
echo  !PROJECT_DIR!\app-output\CastBridge.apk
echo.
echo  To install on your phone:
echo  1. Connect phone via USB [enable USB debugging]
echo  2. Run: adb install app-output\CastBridge.apk
echo  OR copy the APK to your phone and install manually
echo ============================================
pause
endlocal
