# Only a fresh plugin-owned Edge profile is controlled. No default browser profile is read.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Send-Cdp($Method, $Parameters, $Session = $null) {
    $script:Sequence++
    $message = @{ id = $script:Sequence; method = $Method; params = $Parameters }
    if ($Session) { $message.sessionId = $Session }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($message | ConvertTo-Json -Compress -Depth 12) + [char]0)
    $write = $script:PipeInput.WriteAsync($bytes, 0, $bytes.Length)
    if (-not $write.Wait(8000)) { throw 'CDP write timeout' }
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    while ([DateTime]::UtcNow -lt $deadline) {
        $end = [Array]::IndexOf($script:Pending, [byte]0)
        if ($end -lt 0) {
            $buffer = [byte[]]::new(8192)
            $read = $script:PipeOutput.ReadAsync($buffer, 0, $buffer.Length)
            if (-not $read.Wait(8000)) { throw 'CDP read timeout' }
            $count = $read.Result
            if ($count -lt 1 -or $script:Pending.Length + $count -gt 131072) { throw 'Invalid CDP response' }
            $next = [byte[]]::new($script:Pending.Length + $count)
            [Array]::Copy($script:Pending, 0, $next, 0, $script:Pending.Length)
            [Array]::Copy($buffer, 0, $next, $script:Pending.Length, $count)
            $script:Pending = $next
            continue
        }
        $response = [Text.Encoding]::UTF8.GetString($script:Pending, 0, $end) | ConvertFrom-Json
        $next = [byte[]]::new($script:Pending.Length - $end - 1)
        [Array]::Copy($script:Pending, $end + 1, $next, 0, $next.Length)
        $script:Pending = $next
        if ($response.id -eq $script:Sequence) {
            if ($response.error) { throw 'CDP request failed' }
            return $response.result
        }
    }
    throw 'CDP response timeout'
}

function Start-IsolatedEdge($Root, [bool]$Headless) {
    $profile = Join-Path $Root 'profile'
    if (Test-Path -LiteralPath $profile) { throw 'Profile must be fresh' }
    $candidates = @(
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path $env:ProgramFiles 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path $env:LOCALAPPDATA 'Microsoft\Edge\Application\msedge.exe')
    )
    $edge = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $edge) { throw 'EDGE_MISSING' }
    New-Item -ItemType Directory -Path (Join-Path $profile 'Default') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $profile 'Default\Preferences'), '{"credentials_enable_service":false,"profile":{"password_manager_enabled":false}}')
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $edge
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Inherited anonymous handles avoid opening an unauthenticated TCP debugging port.
    $script:PipeInput = [IO.Pipes.AnonymousPipeServerStream]::new([IO.Pipes.PipeDirection]::Out, [IO.HandleInheritability]::Inheritable)
    $script:PipeOutput = [IO.Pipes.AnonymousPipeServerStream]::new([IO.Pipes.PipeDirection]::In, [IO.HandleInheritability]::Inheritable)
    $handles = $script:PipeInput.GetClientHandleAsString() + ',' + $script:PipeOutput.GetClientHandleAsString()
    $start.Arguments = '--user-data-dir="' + $profile + '" --remote-debugging-pipe --remote-debugging-io-pipes=' + $handles + ' --no-first-run --no-default-browser-check --disable-sync --disable-extensions --disable-background-mode '
    if ($Headless) { $start.Arguments += '--headless=new about:blank' }
    else { $start.Arguments += '--app=https://music.163.com/' }
    $script:Browser = [Diagnostics.Process]::Start($start)
    $script:PipeInput.DisposeLocalCopyOfClientHandle()
    $script:PipeOutput.DisposeLocalCopyOfClientHandle()
    $script:DiscardOutput = $script:Browser.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
    $script:DiscardError = $script:Browser.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
    $script:Sequence = 0
    $script:Pending = [byte[]]::new(0)
    Send-Cdp 'Browser.getVersion' @{} | Out-Null
}

function Stop-IsolatedEdge {
    if ($script:PipeInput -and $script:Browser -and -not $script:Browser.HasExited) {
        try { Send-Cdp 'Browser.close' @{} | Out-Null } catch { }
    }
    if ($script:Browser) {
        try { if (-not $script:Browser.WaitForExit(3000)) { $script:Browser.Kill() } } catch { }
        $script:Browser.Dispose()
    }
    if ($script:PipeInput) { $script:PipeInput.Dispose() }
    if ($script:PipeOutput) { $script:PipeOutput.Dispose() }
}

function Invoke-NeteaseWebLogin {
    $root = [Console]::ReadLine()
    $result = @{ error = 'WEB_FAILED' }
    try {
        if (-not [IO.Path]::IsPathRooted($root) -or -not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Invalid workspace' }
        Start-IsolatedEdge $root $false
        $deadline = [DateTime]::UtcNow.AddSeconds(270)
        $targetId = $null
        $session = $null
        while ([DateTime]::UtcNow -lt $deadline) {
            $pages = @((Send-Cdp 'Target.getTargets' @{}).targetInfos | Where-Object { $_.type -eq 'page' })
            if ($targetId) {
                $page = $pages | Where-Object { $_.targetId -eq $targetId } | Select-Object -First 1
                if (-not $page) { $result = @{ error = 'WEB_CLOSED' }; break }
            } else {
                $page = $pages | Where-Object { $_.url -match '^https://music\.163\.com/' } | Select-Object -First 1
                if ($page) {
                    $targetId = $page.targetId
                    $session = (Send-Cdp 'Target.attachToTarget' @{ targetId = $targetId; flatten = $true }).sessionId
                }
            }
            if ($page -and $page.url -match '^https://music\.163\.com/') {
                $cookies = @((Send-Cdp 'Network.getCookies' @{ urls = @('https://music.163.com/', 'https://music.163.com/weapi/w/nuser/account/get', 'https://interface.music.163.com/eapi/song/enhance/player/url/v1') } $session).cookies | Where-Object {
                    $domain = $_.domain.TrimStart('.').ToLowerInvariant()
                    $domain -eq 'music.163.com' -or $domain.EndsWith('.music.163.com')
                })
                if ($cookies.Count -gt 64) { throw 'Cookie bound exceeded' }
                if ($cookies | Where-Object { $_.name -eq 'MUSIC_U' -and $_.value.Length -gt 0 }) {
                    $result = @{ cookies = $cookies }; break
                }
            }
            if ($script:Browser.HasExited) { $result = @{ error = 'WEB_CLOSED' }; break }
            Start-Sleep -Milliseconds 1000
        }
        if (-not $result.cookies -and $result.error -eq 'WEB_FAILED') { $result = @{ error = 'WEB_TIMEOUT' } }
    } catch {
        if ($_.Exception.Message -eq 'EDGE_MISSING') { $result = @{ error = 'EDGE_MISSING' } }
    } finally { Stop-IsolatedEdge }
    # Credentials travel only through the parent's anonymous pipe. Never log raw errors or cookies.
    [Console]::WriteLine(($result | ConvertTo-Json -Depth 12 -Compress))
}
