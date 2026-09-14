# Builds the debug APK and installs it on the phone connected over USB.
# Requires Android Studio (for its bundled JDK and the Android SDK) and USB debugging enabled on the phone.
# Run from PowerShell:  .\install-to-phone.ps1

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = "D:\gradle-builds\exertrack\app\outputs\apk\debug\app-debug.apk"

Set-Location $PSScriptRoot

Write-Host "Checking for a connected phone..."
$devices = & $adb devices | Select-String -Pattern "\sdevice$"
if (-not $devices) {
    Write-Host "No phone found. Plug it in, unlock it, and accept the 'Allow USB debugging' prompt, then run this again."
    exit 1
}

Write-Host "Building..."
.\gradlew.bat assembleDebug --console=plain | Select-String -Pattern "^e:|BUILD"

Write-Host "Installing..."
& $adb install -r $apk
& $adb shell am start -n com.exertrack.app/.MainActivity | Out-Null
Write-Host "Done. Exertrack is open on the phone."
