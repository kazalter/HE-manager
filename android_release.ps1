param(
    [switch]$NoBuild,
    [string]$Device = "f94c0b0",
    [string]$JavaHome = "",
    [string]$MumuPort = ""
)

# Release counterpart of android_preview.ps1. Builds + installs the
# NON-debuggable release APK (debug-signed, so it installs over the debug app
# without uninstall) for judging real performance — startup jank / scroll
# feel. Debug builds are unrepresentative for that.
#
# Deliberately separate from android_preview.ps1 (left untouched per the
# project convention). No -Watch mode: release is a perf checkpoint, not a
# fast edit loop. lintVitalRelease is excluded — it is the biggest avoidable
# release-build cost and irrelevant to perf testing.

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $Root "android-app"
$Gradle = Join-Path $AndroidDir "gradlew.bat"
$PackageName = "com.hemanager.mobile"
$Launcher = "$PackageName/.MainActivity"

function Write-Step($Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Resolve-SdkDir {
    $localProps = Join-Path $AndroidDir "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content -LiteralPath $localProps | Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1
        if ($line) {
            $raw = $line.Substring($line.IndexOf("=") + 1)
            $path = $raw -replace "\\:", ":" -replace "\\\\", "\"
            if (Test-Path $path) { return $path }
        }
    }

    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $defaultSdk) { return $defaultSdk }

    throw "Android SDK not found. Open Android Studio once or update android-app\local.properties."
}

function Resolve-Adb {
    $sdk = Resolve-SdkDir
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (Test-Path $adb) { return $adb }

    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    throw "adb.exe not found. Install Android SDK Platform-Tools from Android Studio SDK Manager."
}

function Resolve-JavaHome {
    if ($JavaHome -and (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
        return $JavaHome
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "$env:LOCALAPPDATA\Android Studio\jbr"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) {
            return $candidate
        }
    }

    throw "Java not found. Pass -JavaHome `"C:\path\to\jdk`" or set JAVA_HOME to Android Studio's bundled JBR."
}

function Get-Devices($Adb) {
    $lines = & $Adb devices | Select-Object -Skip 1
    $devices = @()
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") {
            $devices += $Matches[1]
        }
    }
    return $devices
}

function Try-Connect-Mumu($Adb) {
    $ports = @()
    if ($MumuPort) { $ports += $MumuPort }
    $ports += @("7555", "16384", "16416", "5555")
    $ports = $ports | Select-Object -Unique

    foreach ($port in $ports) {
        Write-Host "Trying MuMu adb port 127.0.0.1:$port..."
        & $Adb connect "127.0.0.1:$port" | Out-Host
        Start-Sleep -Milliseconds 300
        $devices = Get-Devices $Adb
        if ($devices.Count -gt 0) { return $devices }
    }
    return @()
}

function Resolve-Device($Adb) {
    $devices = Get-Devices $Adb
    if ($devices.Count -eq 0) {
        $devices = Try-Connect-Mumu $Adb
    }
    if ($devices.Count -eq 0) {
        throw "No Android device found. Start the intended device, then run this again."
    }
    # Strict device pinning — never silently install on a different device than
    # intended (CLAUDE.md "防双装" rule). Default targets the f* phone; emulators
    # and "hbl…" devices require an explicit -Device flag.
    if ($Device) {
        if ($devices -contains $Device) { return $Device }
        # Tolerate the f-device serial drifting if the requested id is itself
        # an f-prefix one (HE Manager's primary phone format).
        if ($Device -like "f*") {
            $alt = @($devices | Where-Object { $_ -like "f*" })
            if ($alt.Count -gt 0) {
                Write-Host "Requested '$Device' not connected; using '$($alt[0])' instead." -ForegroundColor Yellow
                return $alt[0]
            }
        }
        throw "Requested device '$Device' not connected. Available: $($devices -join ', ')"
    }
    # No -Device flag: refuse to pick anything that is not the f* phone, so an
    # attached emulator or "hbl…" device never gets installed on by accident.
    $preferred = @($devices | Where-Object { $_ -like "f*" })
    if ($preferred.Count -gt 0) { return $preferred[0] }
    throw "No 'f*' phone connected. Pass -Device <id> to target an emulator or other device explicitly. Available: $($devices -join ', ')"
}

function Invoke-Release {
    $adb = Resolve-Adb
    $target = Resolve-Device $adb

    if (-not $NoBuild) {
        $java = Resolve-JavaHome
        $env:JAVA_HOME = $java
        $env:PATH = (Join-Path $java "bin") + ";" + $env:PATH

        # Hard-pin install to the chosen device. Gradle's injected serial is
        # not always honored when multiple devices are attached (that caused
        # the double install); ANDROID_SERIAL forces every adb call (incl.
        # Gradle's install task) to this one device.
        $env:ANDROID_SERIAL = $target

        Write-Step "Building + installing RELEASE app on $target (this is slower than debug)"
        Push-Location $AndroidDir
        try {
            & $Gradle "-Pandroid.injected.adb.device.serial=$target" installRelease "-x" "lintVitalRelease"
            if ($LASTEXITCODE -ne 0) {
                throw "Gradle installRelease failed. If it was INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature clash), run: $adb -s $target uninstall $PackageName  then retry."
            }
        } finally {
            Pop-Location
        }
    } else {
        Write-Step "Skipping build; launching existing release app on $target"
    }

    Write-Step "Launching HE Manager (release)"
    & $adb -s $target shell am start -n $Launcher | Out-Host
    Write-Host ""
    Write-Host "If you are using the Android Studio emulator, enter this server URL in the app:" -ForegroundColor Yellow
    Write-Host "  http://10.0.2.2:8010"
    Write-Host ""
    Write-Host "Perf-testing note:" -ForegroundColor Yellow
    Write-Host "  The baseline profile is installed in the background AFTER first launch."
    Write-Host "  For a true read on startup smoothness: open once, wait ~30s, fully kill"
    Write-Host "  the app, then cold-start again — the 2nd cold start is the optimized one."
}

try {
    Invoke-Release
} finally {
    if (-not $NoBuild) {
        Write-Step "Stopping Gradle Daemon to free memory..."
        Push-Location $AndroidDir
        & $Gradle --stop | Out-Null
        Pop-Location
    }
}
