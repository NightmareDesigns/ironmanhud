@echo off
REM Push IronMan HUD to GitHub
REM Run from: C:\Users\Me\Desktop\IronManHUD_Clean

cd /d "%~dp0"

echo Initializing git...
git init
git add .
git commit -m "IronMan HUD - OpenXR build for Xreal 1s"

echo.
echo Create repo on GitHub.com first, then run:
echo   git remote add origin https://github.com/YOUR_USERNAME/IronManHUD.git
echo   git branch -M main
echo   git push -u origin main
echo.
echo Then: GitHub -> Actions -> "Build Android APK" -> Run workflow
echo Download APK from Artifacts when done.

pause