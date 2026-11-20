<#
.SYNOPSIS
  Runs the macrobenchmarks (baselineprofile module) on the connected device and writes a
  results table to docs/perf/results-<date>-<device>.md.

.EXAMPLE
  tools\perf\run-benchmarks.ps1                    # everything
  tools\perf\run-benchmarks.ps1 -Class StartupBenchmark
  tools\perf\run-benchmarks.ps1 -Serial emulator-5554

.NOTES
  Numbers from an emulator are only good for spotting a change in the same direction; the plan's
  targets are measured on the P2mini. On an emulator the run passes
  androidx.benchmark.suppressErrors=EMULATOR, and the results file says so.
#>
param(
    [string]$Class = "",
    [string]$Serial = ""
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

if ($Serial) { $env:ANDROID_SERIAL = $Serial }
$adbArgs = @(); if ($Serial) { $adbArgs = @("-s", $Serial) }
$model = (& $adb @adbArgs shell getprop ro.product.model).Trim()
$isEmulator = (& $adb @adbArgs shell getprop ro.kernel.qemu).Trim() -eq "1" -or $model -like "sdk_*"

$gradleArgs = @(":baselineprofile:connectedBenchmarkMacrobenchAndroidTest", "--console=plain")
if ($isEmulator) {
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR"
    # Benchmark 1.4.1 drops the shader cache before each cold start with a broadcast that starts
    # the app's process; on the emulator, with nothing compiled, that process is still alive
    # when the library checks it is not ("must not be running prior to cold start").
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dropShaders.enable=false"
}
if ($Class) { $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.class=com.d365fo.mobileops.baselineprofile.$Class" }

Push-Location $root
try {
    # Gradle writes progress to stderr; under "Stop", Windows PowerShell 5.1 would turn its first
    # line into a terminating error. The exit code is what says whether it worked.
    $ErrorActionPreference = "Continue"
    & .\gradlew.bat @gradleArgs 2>&1 | ForEach-Object { "$_" }
    $exit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($exit -ne 0) { throw "benchmark run failed (exit $exit)" }
} finally { Pop-Location }

$json = Get-ChildItem "$root\baselineprofile\build\outputs" -Recurse -Filter "*benchmarkData.json" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $json) { throw "no benchmarkData.json found under baselineprofile\build\outputs" }
$data = Get-Content $json.FullName -Raw | ConvertFrom-Json

$lines = @(
    "# Benchmark results - $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
    "",
    "Device: **$model**$(if ($isEmulator) { ' (emulator - direction only, not absolute numbers)' }) | Commit: $((git -C $root describe --always --dirty).Trim())",
    "",
    "| Test | Metric | P50 | P90 | P99 | Min | Max |",
    "|---|---|---:|---:|---:|---:|---:|"
)
foreach ($b in $data.benchmarks) {
    $test = "$($b.className.Split('.')[-1]).$($b.name)"
    # Single-value metrics (startup) and sampled metrics (frame timings) are in separate maps.
    foreach ($m in $b.metrics.PSObject.Properties) {
        $v = $m.Value
        $lines += "| $test | $($m.Name) | $([math]::Round($v.median,1)) | | | $([math]::Round($v.minimum,1)) | $([math]::Round($v.maximum,1)) |"
    }
    foreach ($m in $b.sampledMetrics.PSObject.Properties) {
        $v = $m.Value
        $lines += "| $test | $($m.Name) | $([math]::Round($v.P50,1)) | $([math]::Round($v.P90,1)) | $([math]::Round($v.P99,1)) | | |"
    }
}

$outDir = Join-Path $root "docs\perf"
New-Item -ItemType Directory -Force $outDir | Out-Null
$safeModel = ($model -replace '[^A-Za-z0-9_-]', '_')
$out = Join-Path $outDir "results-$(Get-Date -Format 'yyyy-MM-dd')-$safeModel.md"
$lines | Out-File -Encoding utf8 $out
Copy-Item $json.FullName (Join-Path $outDir "results-$(Get-Date -Format 'yyyy-MM-dd')-$safeModel.json") -Force
$lines | ForEach-Object { Write-Host $_ }
Write-Host "`nWritten: $out"
