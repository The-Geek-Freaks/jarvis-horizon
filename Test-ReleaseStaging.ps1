[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$Here = [IO.Path]::GetFullPath($PSScriptRoot)
$Manifest = Get-Content -LiteralPath (Join-Path $Here "release-manifest.json") -Raw | ConvertFrom-Json
if ($Manifest.upstream.commit -ne "03619565f017820a090f3fbf9b6571b3c8a3d3da") { throw "Upstream pin mismatch" }
$Assets = Get-Content -LiteralPath (Join-Path $Here "ASSET_LOCK.json") -Raw | ConvertFrom-Json
foreach ($Item in @($Assets.payloads)) {
  $File = [IO.Path]::GetFullPath((Join-Path $Here $Item.path))
  if (!$File.StartsWith($Here + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Invalid payload path" }
  if (!(Test-Path -LiteralPath $File -PathType Leaf)) { throw "Missing payload: $($Item.path)" }
  # On Linux PowerShell treats dotfiles (for example .gitattributes) as hidden.
  # Hashing by literal path works, but metadata lookup needs -Force as well.
  if ((Get-Item -LiteralPath $File -Force).Length -ne $Item.bytes) { throw "Size mismatch: $($Item.path)" }
  if ((Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Item.sha256) { throw "Hash mismatch: $($Item.path)" }
}
$Aar = Join-Path $Here $Assets.customSherpaAar.path
if ((Get-FileHash -LiteralPath $Aar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Assets.customSherpaAar.sha256) { throw "Custom AAR checksum mismatch" }
[void][scriptblock]::Create((Get-Content -LiteralPath (Join-Path $Here "Prepare-AndroidSource.ps1") -Raw))
Write-Host "PASS: upstream pin, $(@($Assets.payloads).Count) payload hashes, custom AAR, bootstrap syntax."
