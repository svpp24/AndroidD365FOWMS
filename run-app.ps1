<#
    run-app.ps1 - build, boot the emulator, install and launch the demo bench.

    Written for this machine: ThinkPad T14 Gen 4, Adoptium JDK 17, Pixel_8 AVD.
    Run it after enabling Intel VT-x in BIOS and installing the emulator hypervisor
    driver; it checks both and tells you which step is still outstanding rather than
    failing with the emulator's rather unhelpful "requires hardware acceleration".

    Usage:
        powershell -ExecutionPolicy Bypass -File .\run-app.ps1
        powershell -ExecutionPolicy Bypass -File .\run-app.ps1 -SkipBuild
        powershell -ExecutionPolicy Bypass -File .\run-app.ps1 -Logcat
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,   # reuse the existing APK
    [switch]$Logcat,      # tail the app's log after launch
    [string]$Avd = "Pixel_8"
)

$ErrorActionPreference = "Stop"

$Sdk      = "$env:LOCALAPPDATA\Android\Sdk"
$Adb      = "$Sdk\platform-tools\adb.exe"
$Emulator = "$Sdk\emulator\emulator.exe"
$Project  = $PSScriptRoot
$Apk      = "$Project\app\build\outputs\apk\debug\app-debug.apk"
$Package  = "com.d365fo.mobileops.debug"          # applicationId + debug suffix
$Activity = "com.d365fo.mobileops.presentation.MainActivity"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"

function Step($n, $text) { Write-Host "`n[$n] $text" -ForegroundColor Cyan }
function Ok($text)       { Write-Host "    OK  $text" -ForegroundColor Green }
function Bad($text)      { Write-Host "    !!  $text" -ForegroundColor Red }

# ---------------------------------------------------------------------------
Step 1 "Checking hardware acceleration"

$vtx = (Get-CimInstance Win32_Processor).VirtualizationFirmwareEnabled
if (-not $vtx) {
    Bad "Intel VT-x is still DISABLED in firmware."
    Write-Host @"

    The emulator cannot start without it.

      Settings > System > Recovery > Advanced startup > Restart now
        > Troubleshoot > Advanced options > UEFI Firmware Settings
      (or tap F1 at the ThinkPad logo)

      Security > Virtualization > Intel (R) Virtualization Technology = Enabled
      F10 to save.  Do NOT load OS Optimized Defaults - it resets this.

    Nothing else here can proceed. Re-run this script after the reboot.
"@ -ForegroundColor Yellow
    exit 1
}
Ok "VT-x enabled in firmware"

& $Emulator -accel-check 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Bad "No hypervisor driver installed (emulator -accel-check exit $LASTEXITCODE)."
    Write-Host @"

    VT-x is on, but the emulator still needs its driver:

      Android Studio > Tools > SDK Manager > SDK Tools
        > tick "Android Emulator hypervisor driver" > Apply

    Then, from an ADMINISTRATOR terminal:
      $Sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat

"@ -ForegroundColor Yellow
    exit 1
}
Ok "Hypervisor driver present"

# ---------------------------------------------------------------------------
Step 2 "Building the debug APK"

if ($SkipBuild -and (Test-Path $Apk)) {
    Ok "Skipped - reusing $(Split-Path $Apk -Leaf)"
} else {
    Push-Location $Project
    try {
        & .\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache
        if ($LASTEXITCODE -ne 0) { Bad "Build failed."; exit 1 }
    } finally { Pop-Location }
    Ok "Built $(Split-Path $Apk -Leaf)"
}

# ---------------------------------------------------------------------------
Step 3 "Starting the emulator"

$attached = (& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" })
if ($attached) {
    Ok "A device is already attached - using it"
} else {
    Start-Process -FilePath $Emulator -ArgumentList "-avd",$Avd,"-no-snapshot-load","-no-boot-anim"
    Write-Host "    waiting for boot (first cold boot can take 2-3 minutes)..."

    & $Adb wait-for-device
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 3
        $booted = (& $Adb shell getprop sys.boot_completed 2>$null)
        if ($booted) { $booted = $booted.Trim() }
    } while ($booted -ne "1" -and (Get-Date) -lt $deadline)

    if ($booted -ne "1") { Bad "Emulator did not finish booting in 5 minutes."; exit 1 }
    Ok "Emulator booted"
}

# ---------------------------------------------------------------------------
Step 4 "Installing"

& $Adb install -r $Apk
if ($LASTEXITCODE -ne 0) { Bad "Install failed."; exit 1 }
Ok "Installed $Package"

# ---------------------------------------------------------------------------
Step 5 "Launching"

& $Adb shell am start -n "$Package/$Activity" | Out-Null
Ok "Started $Activity"

Write-Host @"

    The worklist should show three purchase orders under warehouse WH1.
    It is seeded locally - no D365FO connection is involved.

      Supervisor PIN      1234
      Over-tolerance      PO-000487 line 1: enter 600 against 500 ordered
      Hard block          PO-000487 line 2: no over-delivery permitted
      Bench chips         Network / Rejects / Flaky, above the search box

"@ -ForegroundColor Gray

if ($Logcat) {
    Step 6 "Tailing the log (Ctrl+C to stop)"
    & $Adb logcat -c
    & $Adb logcat -v time -s "D365:*" "DemoSeeder:*" "System.err:*" "AndroidRuntime:E"
}
