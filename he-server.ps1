<#
  HE Manager - backend-only LAN service launcher.

  Thin wrapper that delegates to he.ps1 -Server. Use this when only the
  backend API needs to listen on the LAN (e.g. you're testing the Android
  app or phone web client, and the desktop vite dev server isn't needed).

    he-server.ps1               start backend only on 0.0.0.0:8010
    he-server.ps1 -CleanCache   ignored (no frontend in this mode)

  All the heavy lifting (port cleanup, db backup, graceful shutdown,
  kill-on-close job, encoding, log mirroring) lives in he.ps1 so this
  file stays a one-line dispatcher.
#>
param(
    [switch]$CleanCache
)

$ErrorActionPreference = "Stop"
& "$PSScriptRoot\he.ps1" -Server -CleanCache:$CleanCache @args
