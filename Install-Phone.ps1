# One-click: build debug APK and install it on the connected phone via ADB.
# Usage: right-click > Run with PowerShell, or:  powershell -ExecutionPolicy Bypass -File Install-Phone.ps1
# Optional: specify device:  .\Install-Phone.ps1 -Device 192.168.1.50:5555
param([string]$Device = "")

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (!(Test-Path $adb)) { throw "adb not found at $adb. Install it via Android Studio > SDK Manager > SDK Tools > Android SDK Platform-Tools." }

# The build needs JDK 17+; system Java is often older, so prefer Studio's bundled JDK.
$studioJbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path "$studioJbr\bin\java.exe") { $env:JAVA_HOME = $studioJbr }

Write-Host "==> Building debug APK..."
& "$PSScriptRoot\gradlew.bat" :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) { throw "APK not found: $apk" }

if ($Device -ne "") { & $adb connect $Device }
Write-Host "==> Devices:"
& $adb devices
Write-Host "==> Installing..."
if ($Device -ne "") { & $adb -s $Device install -r $apk }
else { & $adb install -r $apk }
Write-Host "Done. ClearKit should now be on your phone."
