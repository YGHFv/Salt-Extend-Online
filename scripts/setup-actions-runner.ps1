param(
    [Parameter(Mandatory = $true)][string]$RunnerRoot,
    [string]$Proxy = 'http://127.0.0.1:7890'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($RunnerRoot)
if (-not (Test-Path -LiteralPath (Join-Path $root '.runner')) -or
    -not (Test-Path -LiteralPath (Join-Path $root 'run.cmd'))) {
    throw 'RunnerRoot must be an existing configured GitHub Actions runner.'
}
$supervisor = Join-Path $root 'start-runner.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-actions-runner.ps1') -Destination $supervisor -Force
$pwsh = (Get-Command pwsh -ErrorAction Stop).Source
if ($supervisor.Contains('"') -or $Proxy.Contains('"')) { throw 'Invalid runner path or proxy.' }
$arguments = "-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -File `"$supervisor`" -Proxy `"$Proxy`""
$taskName = 'SaltExtendActionsRunner'
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive
$action = New-ScheduledTaskAction -Execute $pwsh -Argument $arguments -WorkingDirectory $root
$logon = New-ScheduledTaskTrigger -AtLogOn -User $principal.UserId
$watchdog = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger @($logon, $watchdog) `
    -Principal $principal -Settings $settings -Description 'Keep the repository-scoped Salt runner online while this user is logged in.' -Force | Out-Null
# Replace the existing logon entry only after the scheduled watchdog is registered.
Remove-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name $taskName -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName $taskName
Write-Output "Installed and started $taskName for $($principal.UserId); supervisor: $supervisor"
