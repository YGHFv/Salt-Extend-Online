param(
    [string]$RunnerRoot = $PSScriptRoot,
    [string]$Proxy = 'http://127.0.0.1:7890'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RunnerRoot)
if (-not (Test-Path -LiteralPath (Join-Path $root '.runner')) -or
    -not (Test-Path -LiteralPath (Join-Path $root 'run.cmd'))) {
    throw 'RunnerRoot must be an existing configured GitHub Actions runner.'
}
$diag = Join-Path $root '_diag'
New-Item -ItemType Directory -Path $diag -Force | Out-Null
$log = Join-Path $diag 'supervisor.log'
$mutex = [Threading.Mutex]::new($false, 'Local\SaltExtendActionsRunner')
$owned = $false
try {
    try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
    if (-not $owned) { exit 0 }
    Set-Location -LiteralPath $root
    $env:HTTPS_PROXY = $Proxy
    $env:HTTP_PROXY = $Proxy
    $env:NO_PROXY = 'localhost,127.0.0.1,::1'
    $env:GIT_CONFIG_COUNT = '1'
    $env:GIT_CONFIG_KEY_0 = 'http.proxy'
    $env:GIT_CONFIG_VALUE_0 = $Proxy
    $env:GIT_TERMINAL_PROMPT = '0'
    while (Test-Path -LiteralPath (Join-Path $root '.runner')) {
        try {
            if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).Length -gt 10485760) {
                $previous = [IO.Path]::GetFullPath((Join-Path $diag 'supervisor.previous.log'))
                if (-not $previous.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'Log rotation target escaped the runner directory.'
                }
                Move-Item -LiteralPath $log -Destination $previous -Force
            }
            "$(Get-Date -Format o) Starting runner listener." | Out-File -LiteralPath $log -Append -Encoding utf8
            # stderr from the native helper is diagnostic output, not a terminating PowerShell error.
            # run.cmd owns the official listener/update/retry lifecycle.
            $ErrorActionPreference = 'Continue'
            try {
                & (Join-Path $root 'run.cmd') 2>&1 | Out-File -LiteralPath $log -Append -Encoding utf8
                $runnerExit = $LASTEXITCODE
            } finally { $ErrorActionPreference = 'Stop' }
            "$(Get-Date -Format o) Runner helper exited ($runnerExit); retrying in 15 seconds." |
                Out-File -LiteralPath $log -Append -Encoding utf8
        } catch {
            "$(Get-Date -Format o) Supervisor error: $($_.Exception.Message); retrying in 15 seconds." |
                Out-File -LiteralPath $log -Append -Encoding utf8 -ErrorAction Continue
        }
        Start-Sleep -Seconds 15
    }
} finally {
    if ($owned) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
