<#
.SYNOPSIS
    Sends one request to the dev VM through the RDP bridge and waits for the answer - see
    README.md. Runs on the PC; vm-watcher.ps1 must be running on the VM.

.EXAMPLE
    tools\vm-bridge\vm.ps1 status
    tools\vm-bridge\vm.ps1 compare
    tools\vm-bridge\vm.ps1 deploy AxClass/HSMobileOpsSkuPageService.xml AxClass/HSMobileOpsCountingService.xml
    tools\vm-bridge\vm.ps1 build -TimeoutSec 1200
    tools\vm-bridge\vm.ps1 -Result 20261122-101500-build     # read an earlier answer
#>
param(
    [Parameter(Position = 0)][ValidateSet('status', 'compare', 'deploy', 'remove', 'fetch', 'find', 'fetchstd', 'build', 'restart', 'dbsync')][string]$Action,
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Files,
    [string[]]$Names,
    [string[]]$Types,
    [string[]]$Packages,
    [string]$Pattern,
    [string[]]$Tables,
    [switch]$IisOnly,
    [int]$Max = 400,
    [int]$TimeoutSec = 540,
    [string]$Result,
    [string]$Bridge = 'C:\Claude\vm-bridge'
)

$ErrorActionPreference = 'Stop'
$requests = Join-Path $Bridge 'requests'
$results  = Join-Path $Bridge 'results'
New-Item -ItemType Directory -Force -Path $requests, $results | Out-Null

function Show-Result([string]$id) {
    $answer = Get-Content -Raw -LiteralPath (Join-Path $results "$id.json") | ConvertFrom-Json
    Write-Output "$($answer.action): $(if ($answer.ok) { 'OK' } else { 'FAILED' })  ($id)"
    Write-Output $answer.summary
    Write-Output "full log: $(Join-Path $results "$id.log")"
    if (-not $answer.ok) { exit 1 }
}

if ($Result) { Show-Result $Result; return }
if (-not $Action) { throw 'an action is needed: status, compare, deploy, build, restart or dbsync' }

$id = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$Action"
$request = [ordered]@{ action = $Action; files = @($Files | Where-Object { $_ }) }
if ($Action -eq 'dbsync' -and $Tables) { $request.tables = @($Tables) }
if ($Action -eq 'restart' -and $IisOnly) { $request.iisOnly = $true }
if ($Action -eq 'find') { $request.names = @($Names); $request.types = @($Types); $request.packages = @($Packages); $request.pattern = $Pattern; $request.max = $Max }
if ($Action -eq 'compare') {
    # Hashed here: the VM reading every repo file over the RDP drive takes many minutes.
    $model = Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) 'aos\HSMobileOps'
    $hashes = [ordered]@{}
    # Only the model's X++ objects (Ax*\*.xml) - the same set the VM compares. The text without
    # BOM and CRs, as vm-watcher.ps1 hashes it: line endings alone don't count as a difference.
    $sha = [Security.Cryptography.SHA256]::Create()
    $utf8 = [Text.UTF8Encoding]::new($false)
    Get-ChildItem -Path (Join-Path $model 'Ax*\*.xml') -File | Sort-Object FullName | ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName).Replace("`r", '')
        $hashes[$_.FullName.Substring($model.Length + 1) -replace '\\', '/'] = -join ($sha.ComputeHash($utf8.GetBytes($text)) | ForEach-Object { $_.ToString('X2') })
    }
    $sha.Dispose()
    $request.hashes = $hashes
}
$body = $request | ConvertTo-Json -Depth 4
$tmp = Join-Path $requests "$id.tmp"
Set-Content -LiteralPath $tmp -Value $body -Encoding UTF8
Move-Item -LiteralPath $tmp -Destination (Join-Path $requests "$id.json")

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while (-not (Test-Path (Join-Path $results "$id.json"))) {
    if ((Get-Date) -gt $deadline) {
        $picked = Test-Path (Join-Path $results "$id.started")
        Write-Output "no answer yet after $TimeoutSec s ($(if ($picked) { 'the VM is working on it' } else { 'not picked up - is vm-watcher.ps1 running in the RDP session?' }))."
        Write-Output "read it later with: tools\vm-bridge\vm.ps1 -Result $id"
        exit 2
    }
    Start-Sleep -Seconds 2
}
Show-Result $id
