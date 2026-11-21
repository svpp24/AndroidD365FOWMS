<#
.SYNOPSIS
    Runs on the D365FO dev VM, inside an RDP session from the PC that holds the repo. Carries out
    build requests that PC drops in its bridge folder - see README.md next to this script.

.DESCRIPTION
    The PC's C: drive is shared into the RDP session as \\tsclient\C. This script watches
    \\tsclient\C\Claude\vm-bridge\requests for *.json requests, does each one, and writes
    results\<id>.json (summary) and results\<id>.log (full output) back.

    Only these actions exist - nothing arbitrary is ever run:
      status   what this VM has: paths, services, admin rights
      compare  repo model files vs the VM's model files (same / different / only on one side)
      deploy   copy the named repo files into the model; each overwritten file is backed up first
      build    compile the model (xppc), errors in the summary
      restart  restart IIS (the AOS) and the batch service, to load a new build
      dbsync   full database sync (only when tables or enums changed; long)

    Start it from an elevated PowerShell on the VM:
      powershell -ExecutionPolicy Bypass -File \\tsclient\C\Claude\d365-mobile-ops\tools\vm-bridge\vm-watcher.ps1
#>
param(
    [string]$Bridge   = '\\tsclient\C\Claude\vm-bridge',
    [string]$Source   = '\\tsclient\C\Claude\d365-mobile-ops\aos\HSMobileOps',
    [string]$Packages = '',
    [string]$Package  = 'HSMobileOps',
    [string]$Model    = 'HSMobileOps',
    [string]$Backups  = 'C:\Temp\vm-bridge-backups'
)

$ErrorActionPreference = 'Stop'

if (-not $Packages) {
    $Packages = @('K:\AosService\PackagesLocalDirectory', 'C:\AosService\PackagesLocalDirectory', 'J:\AosService\PackagesLocalDirectory') |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Packages) { throw 'PackagesLocalDirectory not found - pass -Packages <path>' }
if (-not (Test-Path $Bridge)) { throw "$Bridge not reachable - is the PC's C: drive shared in this RDP session (Local Resources > More > Drives)?" }

$PackageDir = Join-Path $Packages $Package
$ModelDir   = Join-Path $PackageDir $Model
$Requests   = Join-Path $Bridge 'requests'
$Results    = Join-Path $Bridge 'results'
$Done       = Join-Path $Bridge 'done'
New-Item -ItemType Directory -Force -Path $Requests, $Results, $Done, $Backups | Out-Null

# Repo path (relative to aos\HSMobileOps) -> path on the VM. Descriptor sits at package level.
function Get-VmPath([string]$relative) {
    $rel = $relative -replace '/', '\'
    if ($rel -match '(^|\\)\.\.(\\|$)' -or [IO.Path]::IsPathRooted($rel)) { throw "not a model path: $relative" }
    if ($rel -like 'Descriptor\*') { return Join-Path $PackageDir $rel }
    return Join-Path $ModelDir $rel
}

function Get-Hash([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash }

function Invoke-Status {
    $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $services = foreach ($name in 'W3SVC', 'DynamicsAxBatch', 'MSSQLSERVER') {
        $s = Get-Service -Name $name -ErrorAction SilentlyContinue
        if ($s) { "$name=$($s.Status)" } else { "$name=missing" }
    }
    $lines = @(
        "computer: $env:COMPUTERNAME",
        "admin: $admin",
        "packages: $Packages",
        "model folder: $ModelDir (exists: $(Test-Path $ModelDir))",
        "xppc: $(Test-Path (Join-Path $Packages 'bin\xppc.exe'))",
        "SyncEngine: $(Test-Path (Join-Path $Packages 'bin\SyncEngine.exe'))",
        "services: $($services -join ', ')",
        "source: $Source (exists: $(Test-Path $Source))"
    )
    return @{ ok = $true; summary = ($lines -join "`n"); log = ($lines -join "`n") }
}

function Invoke-Compare {
    $repoFiles = Get-ChildItem -LiteralPath $Source -Recurse -File | ForEach-Object { $_.FullName.Substring($Source.Length + 1) }
    $different = @(); $onlyRepo = @(); $same = 0
    foreach ($rel in $repoFiles) {
        $vm = Get-VmPath $rel
        if (-not (Test-Path -LiteralPath $vm)) { $onlyRepo += $rel; continue }
        if ((Get-Hash (Join-Path $Source $rel)) -ne (Get-Hash $vm)) { $different += $rel } else { $same++ }
    }
    $repoSet = @{}; $repoFiles | ForEach-Object { $repoSet[$_.ToLower()] = $true }
    $onlyVm = Get-ChildItem -LiteralPath $ModelDir -Recurse -File -Include *.xml |
        ForEach-Object { $_.FullName.Substring($ModelDir.Length + 1) } |
        Where-Object { -not $repoSet.ContainsKey($_.ToLower()) }
    $log = @("same: $same", '', "different ($($different.Count)):") + $different +
        @('', "only in repo ($($onlyRepo.Count)):") + $onlyRepo +
        @('', "only on VM ($(@($onlyVm).Count)):") + $onlyVm
    $summary = "same $same, different $($different.Count), only in repo $($onlyRepo.Count), only on VM $(@($onlyVm).Count)"
    return @{ ok = $true; summary = $summary; log = ($log -join "`n") }
}

function Invoke-Deploy($files, [string]$id) {
    if (-not $files -or @($files).Count -eq 0) { throw 'deploy needs files' }
    $backup = Join-Path $Backups $id
    $log = @()
    foreach ($rel in $files) {
        # First: refuses anything outside the model (.., absolute paths) before either side is read.
        $to = Get-VmPath $rel
        $from = Join-Path $Source ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $from)) { throw "not in the repo: $rel" }
        if (Test-Path -LiteralPath $to) {
            if ((Get-Hash $from) -eq (Get-Hash $to)) { $log += "unchanged  $rel"; continue }
            $saved = Join-Path $backup ($rel -replace '/', '\')
            New-Item -ItemType Directory -Force -Path (Split-Path $saved) | Out-Null
            Copy-Item -LiteralPath $to -Destination $saved
            $log += "replaced   $rel"
        } else {
            $log += "added      $rel"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path $to) | Out-Null
        Copy-Item -LiteralPath $from -Destination $to -Force
    }
    $log += "backups: $backup"
    return @{ ok = $true; summary = "$(@($files).Count) file(s): " + (($log | Where-Object { $_ -notlike 'backups:*' } | ForEach-Object { ($_ -split '\s+')[0] } | Group-Object | ForEach-Object { "$($_.Count) $($_.Name)" }) -join ', '); log = ($log -join "`n") }
}

function Invoke-Build([string]$id) {
    $bin = Join-Path $Packages 'bin'
    $out = Join-Path $PackageDir 'bin'
    $logFile = Join-Path $Backups "$id-xppc.log"
    $xppcArgs = @(
        "-metadata=$Packages", "-compilermetadata=$Packages", "-output=$out", "-modelmodule=$Package",
        "-xmlLog=$logFile.xml", "-log=$logFile", "-appBase=$bin", "-refPath=$out", "-referenceFolder=$Packages"
    )
    $console = & (Join-Path $bin 'xppc.exe') @xppcArgs 2>&1 | Out-String
    $code = $LASTEXITCODE
    $text = $console
    if (Test-Path $logFile) { $text += "`n----- xppc log -----`n" + (Get-Content -Raw $logFile) }
    $errors = ($text -split "`r?`n") | Where-Object { $_ -match '(?i)\berror\b' -and $_ -notmatch '(?i)\b0 errors?\b' } | Select-Object -Unique -First 60
    $summary = "exit $code, $(@($errors).Count) error line(s)"
    if ($errors) { $summary += "`n" + ($errors -join "`n") }
    return @{ ok = ($code -eq 0 -and -not $errors); summary = $summary; log = $text }
}

function Invoke-Restart {
    $log = @()
    $log += (& iisreset /restart 2>&1 | Out-String)
    if (Get-Service -Name DynamicsAxBatch -ErrorAction SilentlyContinue) {
        Restart-Service -Name DynamicsAxBatch -Force
        $log += "DynamicsAxBatch: $((Get-Service DynamicsAxBatch).Status)"
    }
    return @{ ok = $true; summary = 'IIS and batch restarted'; log = ($log -join "`n") }
}

function Invoke-DbSync {
    $connect = 'Data Source=localhost;Initial Catalog=AxDB;Integrated Security=True;Enlist=True;Application Name=AXVSSDK'
    $console = & (Join-Path $Packages 'bin\SyncEngine.exe') -syncmode=fullall "-metadatabinaries=$Packages" "-connect=$connect" -fallbacktobinary 2>&1 | Out-String
    $code = $LASTEXITCODE
    $tail = (($console -split "`r?`n") | Select-Object -Last 15) -join "`n"
    return @{ ok = ($code -eq 0); summary = "exit $code`n$tail"; log = $console }
}

function Write-Result([string]$id, [string]$action, $result, [datetime]$started) {
    $json = [ordered]@{
        id = $id; action = $action; ok = [bool]$result.ok; summary = [string]$result.summary
        started = $started.ToString('o'); finished = (Get-Date).ToString('o')
    } | ConvertTo-Json
    $tmp = Join-Path $Results "$id.tmp"
    Set-Content -LiteralPath (Join-Path $Results "$id.log") -Value ([string]$result.log) -Encoding UTF8
    Set-Content -LiteralPath $tmp -Value $json -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination (Join-Path $Results "$id.json") -Force
}

Write-Host "vm-bridge: watching $Requests (packages $Packages). Ctrl+C to stop."
while ($true) {
    foreach ($file in (Get-ChildItem -LiteralPath $Requests -Filter *.json | Sort-Object Name)) {
        $started = Get-Date
        $id = $file.BaseName
        $action = '?'
        try {
            $request = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
            $action = [string]$request.action
            Write-Host "$(Get-Date -Format T)  $id  $action ..."
            $result = switch ($action) {
                'status'  { Invoke-Status }
                'compare' { Invoke-Compare }
                'deploy'  { Invoke-Deploy $request.files $id }
                'build'   { Invoke-Build $id }
                'restart' { Invoke-Restart }
                'dbsync'  { Invoke-DbSync }
                default   { @{ ok = $false; summary = "unknown action '$action'"; log = '' } }
            }
        } catch {
            $result = @{ ok = $false; summary = "failed: $($_.Exception.Message)"; log = ($_ | Out-String) }
        }
        Write-Result $id $action $result $started
        Move-Item -LiteralPath $file.FullName -Destination (Join-Path $Done $file.Name) -Force
        Write-Host "$(Get-Date -Format T)  $id  $action -> $(if ($result.ok) { 'ok' } else { 'FAILED' })"
    }
    Start-Sleep -Seconds 2
}
