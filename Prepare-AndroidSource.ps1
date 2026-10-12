[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)] [string] $Destination
 )
$ErrorActionPreference = "Stop"
$Repo = "https://github.com/openclaw/openclaw.git"
$Commit = "03619565f017820a090f3fbf9b6571b3c8a3d3da"
$ExpectedAar = "43f894bedb548210f098dcc0fb2b8c52e1ff576354b8ed53db05840e07015b74"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (Test-Path -LiteralPath $Destination) { throw "Destination already exists: $Destination" }
git clone --filter=blob:none --no-checkout $Repo $Destination
if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
git -C $Destination checkout --detach $Commit
if ($LASTEXITCODE -ne 0) { throw "Pinned checkout failed" }
if ((git -C $Destination rev-parse HEAD).Trim() -ne $Commit) { throw "Pinned checkout identity mismatch" }
Copy-Item -LiteralPath (Join-Path $Here "overlay/apps") -Destination $Destination -Recurse -Force
$Aar = Join-Path $Here "third_party/sherpa-clock1/sherpa-onnx-1.13.8-jarvis-clock1.aar"
if ((Get-FileHash -LiteralPath $Aar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedAar) { throw "Custom AAR checksum mismatch" }
$AarDest = Join-Path $Destination "apps/android/app/libs/sherpa-onnx-1.13.8-jarvis-clock1.aar"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $AarDest) | Out-Null
Copy-Item -LiteralPath $Aar -Destination $AarDest -Force
Write-Host "Prepared at $Destination"
Write-Host "No local credentials, gateway config, device identity, signing key, OTA state, or sessions are copied."
Write-Host "Public package id default: org.thegeekfreaks.jarvishorizon. Namespace/JNI names remain upstream-compatible."
Write-Host "Next: cd $Destination; pnpm install; cd apps/android; ./gradlew :app:assembleThirdPartyDebug"
