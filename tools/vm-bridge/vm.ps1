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
    [Parameter(Position = 0)][ValidateSet('status', 'compare', 'deploy', 'fetch', 'build', 'restart', 'dbsync')][string]$Action,
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Files,
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
if ($Action -eq 'compare') {
    # Hashed here: the VM reading every repo file over the RDP drive takes many minutes.
    $model = Join-Path (Split-Path $PSScriptRoot -Parent | Split-Path -Parent) 'aos\HSMobileOps'
    $hashes = [ordered]@{}
    # Only the model's X++ objects (Ax*\*.xml) - the same set the VM compares.
    Get-ChildItem -Path (Join-Path $model 'Ax*\*.xml') -File | Sort-Object FullName | ForEach-Object {
        $hashes[$_.FullName.Substring($model.Length + 1) -replace '\\', '/'] = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
    }
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
