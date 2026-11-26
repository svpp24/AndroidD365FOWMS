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
      fetch    copy the named model files from the VM to the PC, to diff them there (read-only)
      find     list objects in any package by file name wildcard, optionally containing a regex (read-only)
      fetchstd copy named objects from any package (Package/Model/AxType/Name.xml) to the PC (read-only)
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

# Models on the VM by their descriptors (<package>\Descriptor\<model>.xml): a few hundred small
# files. Listing class folders instead reads hundreds of thousands of files - minutes on the VM.
function Get-Models {
    Get-ChildItem -Path $Packages -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $descriptors = Join-Path $_.FullName 'Descriptor'
        if (Test-Path $descriptors) {
            Get-ChildItem -LiteralPath $descriptors -Filter *.xml -File -ErrorAction SilentlyContinue |
                ForEach-Object { [pscustomobject]@{ Package = $_.Directory.Parent.Name; Model = $_.BaseName } }
        }
    }
}
Write-Host "vm-bridge: starting (packages $Packages)"
# The model may sit under another package or model name than HSMobileOps\HSMobileOps.
# Only when no package was named: a watcher started for another model (-Package) must never
# fall back to the mobile-ops model.
if (-not $PSBoundParameters.ContainsKey('Package') -and -not (Test-Path (Join-Path $Packages "$Package\$Model"))) {
    $found = @(Get-Models | Where-Object { Test-Path (Join-Path $Packages "$($_.Package)\$($_.Model)\AxClass\HSMobileOpsSkuPageService.xml") })
    if ($found.Count -eq 1) {
        $Package = $found[0].Package
        $Model   = $found[0].Model
    }
}
$PackageDir = Join-Path $Packages $Package
$ModelDir   = Join-Path $PackageDir $Model
$boundParams = $PSBoundParameters
$scriptTime  = (Get-Item -LiteralPath $PSCommandPath).LastWriteTime
$Requests   = Join-Path $Bridge 'requests'
$Results    = Join-Path $Bridge 'results'
$Done       = Join-Path $Bridge 'done'
New-Item -ItemType Directory -Force -Path $Requests, $Results, $Done, $Backups | Out-Null

# Repo path (relative to aos\HSMobileOps) -> path on the VM. Descriptor sits at package level.
function Get-VmPath([string]$relative) {
    $rel = $relative -replace '/', '\'
    # The model's descriptor (a new model needs one): <package>\Descriptor\<model>.xml.
    if ($rel -eq "Descriptor\$Model.xml") { return Join-Path $PackageDir $rel }
    # Only the model's X++ objects: <AxType>\<name>.xml. Nothing else in or outside the model.
    # Extensions carry dots (AxMenuExtension\MainMenu.HSMobileOps.xml); each dotted part is
    # non-empty, so '..' never matches.
    if ($rel -notmatch '^Ax[A-Za-z]+\\[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*\.xml$') { throw "not a model object: $relative" }
    return Join-Path $ModelDir $rel
}

function Get-Hash([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash }

# For compare: the text without BOM and CRs, so a file that differs only in line endings (git's
# CRLF checkout against Visual Studio's LF) counts as the same. vm.ps1 hashes the repo the same way.
function Get-TextHash([string]$path) {
    $text = [IO.File]::ReadAllText($path).Replace("`r", '')
    $sha = [Security.Cryptography.SHA256]::Create()
    try { -join ($sha.ComputeHash([Text.UTF8Encoding]::new($false).GetBytes($text)) | ForEach-Object { $_.ToString('X2') }) }
    finally { $sha.Dispose() }
}

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
        "models with HSMobileOpsSkuPageService: $((@(Get-Models | Where-Object { Test-Path (Join-Path $Packages "$($_.Package)\$($_.Model)\AxClass\HSMobileOpsSkuPageService.xml") }) | ForEach-Object { "$($_.Package)\$($_.Model)" }) -join '; ')",
        "models named like HSMobile / HS: $((@(Get-Models | Where-Object { $_.Package -like 'HS*' -or $_.Model -like 'HS*' }) | ForEach-Object { "$($_.Package)\$($_.Model)" }) -join '; ')",
        "xppc: $(Test-Path (Join-Path $Packages 'bin\xppc.exe'))",
        "SyncEngine: $(Test-Path (Join-Path $Packages 'bin\SyncEngine.exe'))",
        "services: $($services -join ', ')",
        "source: $Source (exists: $(Test-Path $Source))"
    )
    return @{ ok = $true; summary = ($lines -join "`n"); log = ($lines -join "`n") }
}

function Invoke-Compare($hashes) {
    # The PC sends the repo's hashes (vm.ps1): reading every repo file over the RDP drive takes many minutes.
    $repoHash = @{}
    if ($hashes) {
        $hashes.PSObject.Properties | ForEach-Object { $repoHash[$_.Name -replace '/', '\'] = $_.Value }
    } else {
        Get-ChildItem -Path (Join-Path $Source 'Ax*\*.xml') -File | ForEach-Object { $repoHash[$_.FullName.Substring($Source.Length + 1)] = Get-TextHash $_.FullName }
    }
    $repoFiles = @($repoHash.Keys | Sort-Object)
    $different = @(); $onlyRepo = @(); $same = 0
    foreach ($rel in $repoFiles) {
        $vm = Get-VmPath $rel
        if (-not (Test-Path -LiteralPath $vm)) { $onlyRepo += $rel; continue }
        if ($repoHash[$rel] -ne (Get-TextHash $vm)) { $different += $rel } else { $same++ }
    }
    $repoSet = @{}; $repoFiles | ForEach-Object { $repoSet[$_.ToLower()] = $true }
    $onlyVm = Get-ChildItem -Path (Join-Path $ModelDir 'Ax*\*.xml') -File |
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

function Invoke-Remove($files, [string]$id) {
    # Deletes the named objects from the model (only Ax*\Name.xml, never the descriptor), each one
    # backed up to C:\Temp\vm-bridge-backups\<id>\ first. Rebuild afterwards.
    if (-not $files -or @($files).Count -eq 0) { throw 'remove needs files' }
    $backup = Join-Path $Backups $id
    $log = @()
    foreach ($rel in $files) {
        if (($rel -replace '/', '\') -like 'Descriptor\*') { throw "remove never deletes the descriptor: $rel" }
        $target = Get-VmPath $rel
        if (-not (Test-Path -LiteralPath $target)) { $log += "missing  $rel"; continue }
        $saved = Join-Path $backup ($rel -replace '/', '\')
        New-Item -ItemType Directory -Force -Path (Split-Path $saved) | Out-Null
        Copy-Item -LiteralPath $target -Destination $saved
        Remove-Item -LiteralPath $target
        $log += "removed  $rel"
    }
    $log += "backups: $backup"
    return @{ ok = $true; summary = ($log -join "`n"); log = ($log -join "`n") }
}

function Invoke-Fetch($files, [string]$id) {
    # Read-only: copies the named model objects from the VM to the PC (bridge\fetched\<id>), for diffing there.
    if (-not $files -or @($files).Count -eq 0) { throw 'fetch needs files' }
    $target = Join-Path (Join-Path $Bridge 'fetched') $id
    $log = @()
    foreach ($rel in $files) {
        $from = Get-VmPath $rel
        if (-not (Test-Path -LiteralPath $from)) { $log += "missing  $rel"; continue }
        $to = Join-Path $target ($rel -replace '/', '\')
        New-Item -ItemType Directory -Force -Path (Split-Path $to) | Out-Null
        Copy-Item -LiteralPath $from -Destination $to -Force
        $log += "fetched  $rel"
    }
    return @{ ok = $true; summary = "$(@($log | Where-Object { $_ -like 'fetched*' }).Count) of $(@($files).Count) fetched to $target"; log = ($log -join "`n") }
}

# Read-only look at standard code. names: file name wildcards (e.g. 'ITM*', '*Markup*'); types: AxType
# folders (default all Ax*); packages: package wildcards (default all); pattern: optional content regex.
function Invoke-Find($request) {
    $names = @($request.names | Where-Object { $_ }); if (-not $names) { throw 'find needs names' }
    foreach ($n in @($names) + @($request.types) + @($request.packages)) { if ($n -and $n -notmatch '^[A-Za-z0-9_*?.]+$') { throw "bad wildcard: $n" } }
    $types = @($request.types | Where-Object { $_ }); if (-not $types) { $types = @('Ax*') }
    $pkgs = @($request.packages | Where-Object { $_ }); if (-not $pkgs) { $pkgs = @('*') }
    $hits = foreach ($pk in $pkgs) { foreach ($t in $types) { foreach ($n in $names) {
        $nn = if ($n -like '*.xml') { $n } else { "$n.xml" }
        Get-ChildItem -Path (Join-Path $Packages "$pk\*\$t\$nn") -File -ErrorAction SilentlyContinue } } }
    $hits = @($hits | Sort-Object FullName -Unique)
    if ($request.pattern) {
        $rx = [string]$request.pattern
        $hits = @($hits | Where-Object { Select-String -LiteralPath $_.FullName -Pattern $rx -Quiet })
    }
    $max = if ($request.max) { [int]$request.max } else { 400 }
    $rel = @($hits | Select-Object -First $max | ForEach-Object { $_.FullName.Substring($Packages.Length + 1) -replace '\\', '/' })
    return @{ ok = $true; summary = "$($hits.Count) match(es)$(if ($hits.Count -gt $max) { ", first $max listed" })`n" + ($rel -join "`n"); log = ($rel -join "`n") }
}

function Invoke-FetchStd($files, [string]$id) {
    if (-not $files -or @($files).Count -eq 0) { throw 'fetchstd needs files' }
    $target = Join-Path (Join-Path $Bridge 'fetched') $id
    $log = @()
    foreach ($relative in $files) {
        $rel = $relative -replace '/', '\'
        # A model object (Package\Model\AxType\Name.xml) or a package's descriptor (Package\Descriptor\Model.xml).
        if ($rel -notmatch '^[A-Za-z0-9_]+\\[A-Za-z0-9_ ]+\\Ax[A-Za-z]+\\[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*\.xml$' -and
            $rel -notmatch '^[A-Za-z0-9_]+\\Descriptor\\[A-Za-z0-9_ ]+\.xml$') { $log += "refused  $relative"; continue }
        $from = Join-Path $Packages $rel
        if (-not (Test-Path -LiteralPath $from)) { $log += "missing  $relative"; continue }
        $to = Join-Path $target $rel
        New-Item -ItemType Directory -Force -Path (Split-Path $to) | Out-Null
        Copy-Item -LiteralPath $from -Destination $to -Force
        $log += "fetched  $relative"
    }
    return @{ ok = $true; summary = "$(@($log | Where-Object { $_ -like 'fetched*' }).Count) of $(@($files).Count) fetched to $target`n" + ($log -join "`n"); log = ($log -join "`n") }
}

function Invoke-Build([string]$id) {
    $bin = Join-Path $Packages 'bin'
    $out = Join-Path $PackageDir 'bin'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $logFile = Join-Path $Backups "$id-xppc.log"
    $xppcArgs = @(
        "-metadata=$Packages", "-compilermetadata=$Packages", "-output=$out", "-modelmodule=$Package",
        "-xmlLog=$logFile.xml", "-log=$logFile", "-appBase=$bin", "-refPath=$out", "-referenceFolder=$Packages"
    )
    $console = & (Join-Path $bin 'xppc.exe') @xppcArgs 2>&1 | Out-String
    $code = $LASTEXITCODE
    $text = $console
    if (Test-Path $logFile) { $text += "`n----- xppc log -----`n" + (Get-Content -Raw $logFile) }
    # The XML log carries the detail (the inner exception of a metadata error, for one).
    if (Test-Path "$logFile.xml") { $text += "`n----- xppc xml log -----`n" + (Get-Content -Raw "$logFile.xml") }
    $errors = ($text -split "`r?`n") | Where-Object { $_ -match '(?i)\berror\b' -and $_ -notmatch '(?i)\b0 errors?\b' } | Select-Object -Unique -First 60
    $summary = "exit $code, $(@($errors).Count) error line(s)"
    if ($errors) { $summary += "`n" + ($errors -join "`n") }
    return @{ ok = ($code -eq 0 -and -not $errors); summary = $summary; log = $text }
}

function Invoke-Restart([bool]$iisOnly) {
    $log = @()
    $log += (& iisreset /restart 2>&1 | Out-String)
    # IIS only: leaves the batch service, and any batch job another user has running, alone.
    if (-not $iisOnly -and (Get-Service -Name DynamicsAxBatch -ErrorAction SilentlyContinue)) {
        Restart-Service -Name DynamicsAxBatch -Force
        $log += "DynamicsAxBatch: $((Get-Service DynamicsAxBatch).Status)"
    }
    $log += "W3SVC: $((Get-Service W3SVC).Status)"
    return @{ ok = $true; summary = $(if ($iisOnly) { 'IIS restarted' } else { 'IIS and batch restarted' }) + "`n" + ($log | Select-Object -Last 1); log = ($log -join "`n") }
}

function Invoke-DbSync($tables) {
    $connect = 'Data Source=localhost;Initial Catalog=AxDB;Integrated Security=True;Enlist=True;Application Name=AXVSSDK'
    $list = @($tables | Where-Object { $_ })
    foreach ($t in $list) { if ($t -notmatch '^[A-Za-z0-9_]+$') { throw "bad table name: $t" } }
    # Named tables only (partial sync; the list is one argument so its commas stay inside it), or everything.
    $mode = if ($list) { @('-syncmode=partiallist', ('-synclist=' + ($list -join ','))) } else { @('-syncmode=fullall') }
    $console = & (Join-Path $Packages 'bin\SyncEngine.exe') @mode "-metadatabinaries=$Packages" "-connect=$connect" -fallbacktobinary 2>&1 | Out-String
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
    # The PC changed this script: start again with the new version (no restart in the RDP session).
    if ((Get-Item -LiteralPath $PSCommandPath).LastWriteTime -ne $scriptTime) {
        Write-Host "$(Get-Date -Format T)  script changed - reloading"
        & $PSCommandPath @boundParams
        return
    }
    foreach ($file in (Get-ChildItem -LiteralPath $Requests -Filter *.json | Sort-Object Name)) {
        $started = Get-Date
        $id = $file.BaseName
        # Seen by vm.ps1: picked up and working, however long it takes.
        Set-Content -LiteralPath (Join-Path $Results "$id.started") -Value $started.ToString('o')
        $action = '?'
        try {
            $request = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
            $action = [string]$request.action
            Write-Host "$(Get-Date -Format T)  $id  $action ..."
            $result = switch ($action) {
                'status'  { Invoke-Status }
                'compare' { Invoke-Compare $request.hashes }
                'deploy'  { Invoke-Deploy $request.files $id }
                'fetch'   { Invoke-Fetch $request.files $id }
                'remove'  { Invoke-Remove $request.files $id }
                'find'    { Invoke-Find $request }
                'fetchstd' { Invoke-FetchStd $request.files $id }
                'build'   { Invoke-Build $id }
                'restart' { Invoke-Restart ([bool]$request.iisOnly) }
                'dbsync'  { Invoke-DbSync $request.tables }
                default   { @{ ok = $false; summary = "unknown action '$action'"; log = '' } }
            }
        } catch {
            $result = @{ ok = $false; summary = "failed: $($_.Exception.Message)"; log = ($_ | Out-String) }
        }
        Write-Result $id $action $result $started
        # The PC may have withdrawn the request meanwhile: not an error.
        Move-Item -LiteralPath $file.FullName -Destination (Join-Path $Done $file.Name) -Force -ErrorAction SilentlyContinue
        Write-Host "$(Get-Date -Format T)  $id  $action -> $(if ($result.ok) { 'ok' } else { 'FAILED' })"
    }
    Start-Sleep -Seconds 2
}
