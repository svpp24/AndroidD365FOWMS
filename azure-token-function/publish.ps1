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
# Not Compress-Archive: it writes no folder entries, and Azure's check wants the .azurefunctions
# folder itself at the root of the zip ("Cannot find required .azurefunctions directory").
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($zip, 'Create')
try {
    Get-ChildItem $out -Recurse -Force | Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($out.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, '/')
        if ($_.PSIsContainer) { [void]$archive.CreateEntry($rel + '/') }
        else { [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $rel) }
    }
} finally { $archive.Dispose() }
Write-Host "Built $zip"
