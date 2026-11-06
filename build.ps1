param([switch]$SetupTools)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$toolsRoot = Join-Path $env:LOCALAPPDATA 'Programs\REA-Android\build-tools'
$gradlePath = Join-Path $toolsRoot 'gradle-8.14.3\bin\gradle.bat'
if ($SetupTools -or -not (Test-Path -LiteralPath $gradlePath) -or -not (Test-Path -LiteralPath local.properties)) {
    python scripts/bootstrap_android.py
    if ($LASTEXITCODE -ne 0) { throw 'Android build tools could not be prepared.' }
}
& $gradlePath --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
New-Item -ItemType Directory -Force artifacts | Out-Null
Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination artifacts/HGTV-Clean-0.3.0-debug.apk
Get-FileHash -LiteralPath artifacts/HGTV-Clean-0.3.0-debug.apk -Algorithm SHA256
