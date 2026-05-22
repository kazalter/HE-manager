<#
  HE Manager - single-window launcher for the web stack.

  Two entry points (this file is the engine for both):

    he.ps1                  Full stack: backend (--reload) + frontend (vite)
                            + open browser. Use for desktop development.

    he-server.ps1           Backend-only service: backend without --reload,
                            no frontend, no browser. Use when only the API
                            needs to listen (phone / Android app / curl on
                            the LAN). he-server.ps1 is a thin wrapper that
                            calls "he.ps1 -Server".

  Flags:
    -Server         backend only, no frontend, no auto-reload
    -CleanCache     also wipe the vite dependency cache (full-stack only)

  Stop with Q or Ctrl+C. Closing the window also kills child processes
  (Win32 Job Object with KILL_ON_JOB_CLOSE).

  Before starting the backend, library.db is snapshotted into
  backend/backups/ (newest 7 kept) so a corrupted db can be rolled back.

  ASCII-only on purpose: Windows PowerShell 5.1 mis-reads non-ASCII in a
  BOM-less UTF-8 script on a GBK console. Child output is force-decoded
  as UTF-8 and de-ANSI'd so Chinese logs / arrows render correctly.

  Android build/install is a separate concern - see android_preview.ps1.
#>
param(
    [switch]$Server,
    [switch]$CleanCache
)

$ErrorActionPreference = "Stop"

# --- console encoding: print captured UTF-8 child output correctly ---
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# --- kill-on-close job object ---------------------------------------------
# Closing the console window (the X button) kills THIS powershell process
# without running the finally{} block, so Stop-Child never fires and uvicorn
# / node keep serving on the ports forever. A Win32 Job Object created with
# JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE makes Windows terminate every child the
# moment our process (and thus the job handle) dies, for ANY exit reason -
# X button, taskkill, crash. The finally{} path still does a graceful tree
# kill; this is the safety net for the ungraceful one.
$script:KillJob = $null
try {
    if (-not ("HE.KillJob" -as [type])) {
        Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace HE {
  public static class KillJob {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)]
    static extern IntPtr CreateJobObject(IntPtr a, string n);
    [DllImport("kernel32.dll")]
    static extern bool SetInformationJobObject(IntPtr j, int t, IntPtr i, uint l);
    [DllImport("kernel32.dll")]
    static extern bool AssignProcessToJobObject(IntPtr j, IntPtr p);
    [StructLayout(LayoutKind.Sequential)]
    struct BASIC { public long a; public long b; public uint LimitFlags;
      public UIntPtr c; public UIntPtr d; public uint e; public UIntPtr f;
      public uint g; public uint h; }
    [StructLayout(LayoutKind.Sequential)]
    struct IOC { public ulong a,b,c,d,e,f; }
    [StructLayout(LayoutKind.Sequential)]
    struct EXT { public BASIC Basic; public IOC Io; public UIntPtr a,b,c,d; }
    public static IntPtr Create() {
      IntPtr h = CreateJobObject(IntPtr.Zero, null);
      if (h == IntPtr.Zero) return IntPtr.Zero;
      EXT e = new EXT();
      e.Basic.LimitFlags = 0x2000; // KILL_ON_JOB_CLOSE
      int len = Marshal.SizeOf(typeof(EXT));
      IntPtr p = Marshal.AllocHGlobal(len);
      Marshal.StructureToPtr(e, p, false);
      SetInformationJobObject(h, 9, p, (uint)len);
      Marshal.FreeHGlobal(p);
      return h;
    }
    public static void Assign(IntPtr job, IntPtr proc) {
      if (job != IntPtr.Zero) AssignProcessToJobObject(job, proc);
    }
  }
}
"@
    }
    $script:KillJob = [HE.KillJob]::Create()
} catch {
    Write-Host "  (kill-on-close job unavailable: $($_.Exception.Message))" -ForegroundColor DarkGray
}

$Root        = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir  = Join-Path $Root "backend"
$FrontendDir = Join-Path $Root "frontend"
$LogDir      = Join-Path $Root "logs"
$BackendLog  = Join-Path $LogDir "backend.log"
$FrontendLog = Join-Path $LogDir "frontend.log"
$BackendPort  = 8010
$FrontendPort = 5173
# Live SQLite database (read by backend/app/database.py via __file__).
$DbFile      = Join-Path $BackendDir "app\library.db"
$BackupDir   = Join-Path $BackendDir "backups"
$BackupKeep  = 7   # one snapshot per launch, keep the newest 7

# Old run_app.bat accepted --clean-cache / --clean; keep that working.
if ($args -contains "--clean-cache" -or $args -contains "--clean") { $CleanCache = $true }

$ModeLabel = if ($Server) { "server (backend only, no reload)" } else { "dev (full stack, auto-reload)" }
$AnsiEsc = [char]27
$AnsiPattern = "$AnsiEsc\[[0-9;?]*[ -/]*[@-~]"

function Write-Banner($Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Backup-Database {
    # Copy library.db (+ -wal / -shm if present) to backend/backups/ before the
    # next backend starts. The previous backend was just force-killed by
    # Stop-PortListeners/Stop-StrayBackends, so the files aren't locked but the
    # WAL may still hold un-merged writes. Copying all three sidecar files
    # gives a transaction-consistent snapshot rather than just the main db.
    if (-not (Test-Path -LiteralPath $DbFile)) {
        Write-Host "  (no db at $DbFile, skipping backup)" -ForegroundColor DarkGray
        return
    }
    $size = (Get-Item -LiteralPath $DbFile).Length
    if ($size -eq 0) {
        Write-Host "  (db is 0 bytes, skipping backup of empty file)" -ForegroundColor DarkGray
        return
    }
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $dest  = Join-Path $BackupDir "library-$stamp.db"
    try {
        Copy-Item -LiteralPath $DbFile -Destination $dest -Force -ErrorAction Stop
        foreach ($suffix in @("-wal", "-shm")) {
            $src = "$DbFile$suffix"
            if (Test-Path -LiteralPath $src) {
                Copy-Item -LiteralPath $src -Destination "$dest$suffix" -Force -ErrorAction SilentlyContinue
            }
        }
        $mb = [math]::Round($size / 1MB, 2)
        Write-Host "  backed up library.db ($mb MB) -> library-$stamp.db" -ForegroundColor DarkGray
    } catch {
        Write-Host "  (backup failed: $($_.Exception.Message))" -ForegroundColor Yellow
        return
    }
    # Rotation: keep the newest $BackupKeep by name (timestamp), drop the rest.
    $existing = Get-ChildItem -LiteralPath $BackupDir -Filter "library-*.db" -File |
        Sort-Object Name -Descending
    if ($existing.Count -gt $BackupKeep) {
        $existing | Select-Object -Skip $BackupKeep | ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
            foreach ($suffix in @("-wal", "-shm")) {
                Remove-Item -LiteralPath "$($_.FullName)$suffix" -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

function Stop-ProcessGracefully($ProcId, $GraceSeconds = 4) {
    # Graceful first, /F fallback. Plain taskkill (no /F) posts WM_CLOSE, which
    # uvicorn handles like SIGINT: it runs the shutdown event, lets SQLAlchemy
    # drain connections, and triggers a clean WAL checkpoint. /F mid-checkpoint
    # is what causes "database disk image is malformed" on next launch.
    try {
        & taskkill /PID $ProcId /T 2>$null | Out-Null
    } catch { }
    $deadline = (Get-Date).AddSeconds($GraceSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $p = Get-Process -Id $ProcId -ErrorAction Stop
            if ($p.HasExited) { return }
        } catch {
            return  # process already gone
        }
        Start-Sleep -Milliseconds 200
    }
    # Grace expired, force-kill as last resort.
    try { & taskkill /PID $ProcId /T /F 2>$null | Out-Null } catch { }
}

function Stop-PortListeners($Ports) {
    $procIds = $Ports |
        ForEach-Object { Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue } |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $procIds) {
        if ($procId) {
            Write-Host "  closing PID $procId" -ForegroundColor DarkGray
            Stop-ProcessGracefully $procId
        }
    }
}

function Stop-StrayBackends {
    # Stop-PortListeners only catches whatever currently owns 8010/5173. A
    # uvicorn started OUTSIDE he.ps1 (old run_server.bat, a crashed prior run
    # that rebound the port, a second copy on another interface) keeps serving
    # STALE code, so newly added routes 404 as {"detail":"Not Found"}. Kill
    # any leftover backend by command line, regardless of port.
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*uvicorn app.main:app*" } |
        ForEach-Object {
            Write-Host "  killing stray backend PID $($_.ProcessId)" -ForegroundColor DarkGray
            Stop-ProcessGracefully $_.ProcessId
        }
}

function Wait-Port($Port, $TimeoutSec = 45) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $c = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $c.BeginConnect("127.0.0.1", $Port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(500)) {
                $c.EndConnect($async); $c.Close(); return $true
            }
        } catch { } finally { $c.Close() }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-LanIp {
    try {
        $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" -and $_.IPAddress -ne "198.18.0.1" } |
            Select-Object -ExpandProperty IPAddress -First 1
        return $ip
    } catch { return $null }
}

function Start-Child($FileName, $Arguments, $WorkDir, $Prefix, $Color, $LogPath) {
    Set-Content -LiteralPath $LogPath -Value "" -Encoding utf8
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $FileName
    $psi.Arguments              = $Arguments
    $psi.WorkingDirectory       = $WorkDir
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    # Redirect stdin so the child can't grab the console keyboard. Vite
    # (npm run dev) otherwise puts the terminal in raw keypress mode and
    # eats our Q / Ctrl+C; with a non-TTY stdin it disables that, leaving
    # the console free for the [Console]::ReadKey loop below.
    $psi.RedirectStandardInput  = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding  = [System.Text.Encoding]::UTF8
    # Discourage ANSI color at the source; force UTF-8 from python.
    $psi.EnvironmentVariables["NO_COLOR"]        = "1"
    $psi.EnvironmentVariables["FORCE_COLOR"]     = "0"
    $psi.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8"
    $psi.EnvironmentVariables["PYTHONUTF8"]       = "1"
    # watchfiles' native FS events are unreliable on Windows when the watched
    # path contains a space (this repo is "D:\HE manager\..."), so edited
    # backend code silently fails to hot-reload. Polling is reliable
    # regardless of the path; harmless for the vite child (it ignores it).
    $psi.EnvironmentVariables["WATCHFILES_FORCE_POLLING"] = "1"
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $msg = @{ Prefix = $Prefix; Color = $Color; Log = $LogPath; Ansi = $AnsiPattern }
    $sink = {
        $line = $EventArgs.Data
        if ($null -eq $line) { return }
        $m = $Event.MessageData
        $clean = [regex]::Replace($line, $m.Ansi, "")
        Write-Host ("[{0}] {1}" -f $m.Prefix, $clean) -ForegroundColor $m.Color
        Add-Content -LiteralPath $m.Log -Value $clean -Encoding utf8
    }
    Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $sink -MessageData $msg | Out-Null
    Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived  -Action $sink -MessageData $msg | Out-Null
    [void]$proc.Start()
    # Assign to the kill-on-close job immediately (before cmd.exe spawns
    # node) so the whole tree dies with us. Job membership is inherited by
    # child processes, so node/uvicorn-reloader are covered too.
    try { [HE.KillJob]::Assign($script:KillJob, $proc.Handle) } catch { }
    $proc.StandardInput.Close()   # signal EOF; child stops watching stdin
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()
    return $proc
}

function Stop-Child($Proc) {
    if ($Proc -and -not $Proc.HasExited) {
        # Same graceful+force pattern as Stop-ProcessGracefully; taskkill /T
        # walks the whole tree (uvicorn --reload spawns a reloader child).
        Stop-ProcessGracefully $Proc.Id 4
    }
}

$backend = $null
$frontend = $null
$prevCtrlC = [Console]::TreatControlCAsInput

try {
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "  HE Manager - $ModeLabel" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan

    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    Write-Banner "Cleaning up listeners on $BackendPort / $FrontendPort"
    Stop-PortListeners @($BackendPort, $FrontendPort)
    Stop-StrayBackends

    Write-Banner "Backing up library.db"
    Backup-Database

    # CleanCache is meaningless in server-only mode (no frontend).
    if ($CleanCache -and -not $Server) {
        $viteCache = Join-Path $FrontendDir "node_modules\.vite"
        if (Test-Path $viteCache) {
            Write-Host "  wiping vite cache" -ForegroundColor DarkGray
            Remove-Item -Recurse -Force $viteCache
        }
    }

    # Server mode runs the backend as a stable LAN service, so we disable
    # auto-reload (watchfiles polling churns CPU and a phone client would
    # see request floods on every save).
    $reloadArgs = if ($Server) {
        ""
    } else {
        " --reload --reload-dir `"$BackendDir\app`" --reload-exclude `"*.db`""
    }
    $backendArgs = "-m uvicorn app.main:app --app-dir `"$BackendDir`" --host 0.0.0.0 --port $BackendPort --no-access-log" + $reloadArgs

    Write-Banner "Starting backend"
    $backend = Start-Child "python" $backendArgs $BackendDir "BE" "Cyan" $BackendLog

    if (-not $Server) {
        Write-Banner "Starting frontend"
        $frontend = Start-Child "cmd.exe" "/c npm run dev -- --host" $FrontendDir "FE" "Magenta" $FrontendLog
    }

    Write-Banner "Waiting for services"
    if (Wait-Port $BackendPort)  { Write-Host "  [OK] backend  :$BackendPort"  -ForegroundColor Green }
    else                         { Write-Host "  [!] backend timed out, continuing" -ForegroundColor Yellow }
    if (-not $Server) {
        if (Wait-Port $FrontendPort) { Write-Host "  [OK] frontend :$FrontendPort" -ForegroundColor Green }
        else                         { Write-Host "  [!] frontend timed out, continuing" -ForegroundColor Yellow }
        Start-Process "http://localhost:$FrontendPort" | Out-Null
    }

    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "  HE Manager is running" -ForegroundColor Green
    $lan = Get-LanIp
    if ($Server) {
        Write-Host "  Backend API only (no frontend)"
        Write-Host "  Local   http://localhost:$BackendPort"
        if ($lan) { Write-Host "  LAN     http://${lan}:$BackendPort   (phone / Android app)" }
        Write-Host "  Logs    $BackendLog"
    } else {
        Write-Host "  Local   http://localhost:$FrontendPort"
        if ($lan) { Write-Host "  LAN     http://${lan}:$FrontendPort   (phone / API: http://${lan}:$BackendPort)" }
        Write-Host "  Logs    $BackendLog"
        Write-Host "          $FrontendLog"
    }
    Write-Host "  Press Q or Ctrl+C to stop everything." -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host ""

    [Console]::TreatControlCAsInput = $true
    while ($true) {
        if ($backend.HasExited)  { Write-Host "[BE] process exited ($($backend.ExitCode))"  -ForegroundColor Yellow; break }
        if ($frontend -and $frontend.HasExited) { Write-Host "[FE] process exited ($($frontend.ExitCode))" -ForegroundColor Yellow; break }
        if ([Console]::KeyAvailable) {
            $k = [Console]::ReadKey($true)
            $isCtrlC = ($k.Modifiers -band [ConsoleModifiers]::Control) -and ($k.Key -eq 'C')
            if ($isCtrlC -or $k.Key -eq 'Q') { Write-Host ""; Write-Host "Stopping..." -ForegroundColor Yellow; break }
        }
        Start-Sleep -Milliseconds 200
    }
}
finally {
    [Console]::TreatControlCAsInput = $prevCtrlC
    Stop-Child $backend
    Stop-Child $frontend
    # Belt-and-suspenders: free the ports in case a grandchild lingered.
    Stop-PortListeners @($BackendPort, $FrontendPort)
    Stop-StrayBackends
    Get-EventSubscriber -ErrorAction SilentlyContinue | Unregister-Event -ErrorAction SilentlyContinue
    Write-Host "Stopped. Ports $BackendPort / $FrontendPort freed." -ForegroundColor Green
}
