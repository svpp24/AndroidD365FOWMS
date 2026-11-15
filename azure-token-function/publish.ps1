<#
.SYNOPSIS
  Builds token-function.zip - the C# Function App, ready to deploy (setup.ps1, or
  az functionapp deployment source config-zip). Needs the .NET 10 SDK.
#>
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'publish'
$zip = Join-Path $PSScriptRoot 'token-function.zip'
Remove-Item $out, $zip -Recurse -Force -ErrorAction SilentlyContinue
dotnet test (Join-Path $PSScriptRoot 'TokenFunction.Tests') --nologo
if ($LASTEXITCODE -ne 0) { throw 'tests failed - nothing published' }
dotnet publish (Join-Path $PSScriptRoot 'TokenFunction') -c Release -o $out --nologo
if ($LASTEXITCODE -ne 0) { throw 'publish failed' }
Compress-Archive -Path (Join-Path $out '*') -DestinationPath $zip
Write-Host "Built $zip"
