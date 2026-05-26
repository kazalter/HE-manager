param(
    [switch]$Watch,
    [switch]$NoBuild,
    [string]$Device = "f94c0b0",
    [string]$JavaHome = "",
    [string]$MumuPort = ""
)

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
        throw "No Android device found. Start Android Studio emulator or MuMu, then run this again."
    }
    # Strict device pinning — never silently install on a different device than
    # intended (CLAUDE.md "防双装" rule). Default targets the f* phone; emulators
    # require an explicit -Device flag.
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

function Invoke-Preview {
    $adb = Resolve-Adb
    $target = Resolve-Device $adb

    if (-not $NoBuild) {
        $java = Resolve-JavaHome
        $env:JAVA_HOME = $java
        $env:PATH = (Join-Path $java "bin") + ";" + $env:PATH

        # Hard-pin install to the chosen device. Gradle's injected serial is
        # not always honored when multiple devices are attached (that caused
        # the double install — same fix as android_release.ps1); ANDROID_SERIAL
        # forces every adb call (incl. Gradle's installDebug) to this one device.
        $env:ANDROID_SERIAL = $target

        Write-Step "Building and installing debug app on $target"
        Push-Location $AndroidDir
        try {
            & $Gradle "-Pandroid.injected.adb.device.serial=$target" installDebug
            if ($LASTEXITCODE -ne 0) { throw "Gradle installDebug failed." }
        } finally {
            Pop-Location
        }
    } else {
        Write-Step "Skipping build; launching existing app on $target"
    }

    Write-Step "Launching HE Manager"
    & $adb -s $target shell am start -n $Launcher | Out-Host
    Write-Host ""
    Write-Host "If you are using the Android Studio emulator, enter this server URL in the app:" -ForegroundColor Yellow
    Write-Host "  http://10.0.2.2:8010"
}

function Watch-AndroidFiles {
    Invoke-Preview

    Write-Step "Watching android-app source files"
    Write-Host "Press Ctrl+C to stop."

    $watcher = New-Object System.IO.FileSystemWatcher
    $watcher.Path = Join-Path $AndroidDir "app"
    $watcher.IncludeSubdirectories = $true
    $watcher.EnableRaisingEvents = $true
    $watcher.NotifyFilter = [IO.NotifyFilters]'FileName, LastWrite, Size'

    $lastRun = Get-Date "2000-01-01"
    $action = {
        $path = $Event.SourceEventArgs.FullPath
        if ($path -notmatch "\.(java|xml|gradle)$") { return }
        $now = Get-Date
        if (($now - $script:lastRun).TotalSeconds -lt 2) { return }
        $script:lastRun = $now
        Start-Sleep -Milliseconds 800
        try {
            Invoke-Preview
        } catch {
            Write-Host $_.Exception.Message -ForegroundColor Red
        }
    }

    Register-ObjectEvent $watcher Changed -Action $action | Out-Null
    Register-ObjectEvent $watcher Created -Action $action | Out-Null
    Register-ObjectEvent $watcher Deleted -Action $action | Out-Null
    Register-ObjectEvent $watcher Renamed -Action $action | Out-Null

    while ($true) { Start-Sleep -Seconds 1 }
}

try {
    if ($Watch) {
        Watch-AndroidFiles
    } else {
        Invoke-Preview
    }
} finally {
    if (-not $NoBuild) {
        Write-Step "Stopping Gradle Daemon to free memory..."
        Push-Location $AndroidDir
        & $Gradle --stop | Out-Null
        Pop-Location
    }
}
