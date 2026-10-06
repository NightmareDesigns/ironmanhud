@echo off
REM build_and_run.bat - Quick build script for IronMan HUD (Windows)
REM Usage: build_and_run.bat [device]
REM device: pixel, s24, s24ultra, auto (default)

set PROJECT_PATH=%USERPROFILE%\Desktop\IronManHUD
set UNITY_PATH="C:\Program Files\Unity\Hub\Editor\2022.3.21f1\Editor\Unity.exe"

set DEVICE=%1
if "%DEVICE%"=="" set DEVICE=auto

echo 🔨 Building IronMan HUD for %DEVICE%...

cd /d "%PROJECT_PATH%"

REM Clean previous build
if exist Builds\IronManHUD.apk del Builds\IronManHUD.apk

REM Build
%UNITY_PATH% -batchmode ^
  -projectPath "%PROJECT_PATH%" ^
  -executeMethod StarkIndustries.Editor.BuildScript.BuildForDevice ^
  -device %DEVICE% ^
  -buildPath "%PROJECT_PATH%\Builds\IronManHUD.apk" ^
  -logFile - ^
  -quit

if exist "%PROJECT_PATH%\Builds\IronManHUD.apk" (
    echo ✅ Build successful: Builds\IronManHUD.apk
    
    REM Install if device connected
    where adb >nul 2>nul
    if %errorlevel% equ 0 (
        echo 📱 Installing to connected device...
        adb install -r "%PROJECT_PATH%\Builds\IronManHUD.apk"
        echo 🚀 Launching...
        adb shell am start -n com.starkindustries.ironmanhud/com.unity3d.player.UnityPlayerActivity
    ) else (
        echo ⚠️ ADB not found. Install manually: adb install Builds\IronManHUD.apk
    )
) else (
    echo ❌ Build failed
    exit /b 1
)