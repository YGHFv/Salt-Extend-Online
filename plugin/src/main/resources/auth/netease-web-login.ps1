# Only a fresh plugin-owned Edge profile is controlled. No default browser profile is read.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Send-Cdp($Method, $Parameters, $Session = $null, [int]$TimeoutMillis = 8000) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $script:Sequence++
    $message = @{ id = $script:Sequence; method = $Method; params = $Parameters }
    if ($Session) { $message.sessionId = $Session }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($message | ConvertTo-Json -Compress -Depth 12) + [char]0)
    $write = $script:PipeInput.WriteAsync($bytes, 0, $bytes.Length)
    if (-not $write.Wait($TimeoutMillis)) { throw 'CDP write timeout' }
    while ($timer.ElapsedMilliseconds -lt $TimeoutMillis) {
        $end = [Array]::IndexOf($script:Pending, [byte]0)
        if ($end -lt 0) {
            $buffer = [byte[]]::new(8192)
            $read = $script:PipeOutput.ReadAsync($buffer, 0, $buffer.Length)
            $remaining = [Math]::Max(1, $TimeoutMillis - [int]$timer.ElapsedMilliseconds)
            if (-not $read.Wait($remaining)) { throw 'CDP read timeout' }
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

function Get-EdgeRegistryValues([string]$SubKey, [string]$ValueName, [bool]$Required = $false) {
    foreach ($hive in @([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryHive]::LocalMachine)) {
        foreach ($view in @([Microsoft.Win32.RegistryView]::Registry64, [Microsoft.Win32.RegistryView]::Registry32)) {
            $base = $null
            $key = $null
            try {
                $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
                $key = $base.OpenSubKey($SubKey, $false)
                if ($key) {
                    $value = $key.GetValue($ValueName, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                    if ($null -ne $value) { $value }
                }
            } catch {
                if ($Required) { throw 'EDGE_POLICY' }
            } finally {
                if ($key) { $key.Dispose() }
                if ($base) { $base.Dispose() }
            }
        }
    }
}

function Resolve-EdgeExecutable($Candidate) {
    if ($Candidate -isnot [string] -or [string]::IsNullOrWhiteSpace($Candidate) -or $Candidate -match '[\x00-\x1f]') { return }
    try {
        $path = [Environment]::ExpandEnvironmentVariables($Candidate.Trim())
        if ($path.StartsWith('"') -and $path.EndsWith('"') -and $path.Length -gt 2) {
            $path = $path.Substring(1, $path.Length - 2)
        }
        # App Paths contains an executable path, never a shell command or arguments.
        if ($path -match '["\x00-\x1f]' -or $path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+[\\/])') { return }
        $path = [IO.Path]::GetFullPath($path)
        if ([IO.Path]::GetFileName($path) -ine 'msedge.exe') { return }
        if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
    } catch { }
}

function Get-EdgeInstallDirectories {
    return @(
        ${env:ProgramFiles(x86)}, $env:ProgramFiles, $env:ProgramW6432, $env:LOCALAPPDATA,
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFilesX86),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles),
        [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    )
}

function Find-EdgeExecutable {
    foreach ($directory in @(Get-EdgeInstallDirectories)) {
        if ([string]::IsNullOrWhiteSpace($directory)) { continue }
        $edge = $null
        try { $edge = Resolve-EdgeExecutable ([IO.Path]::Combine($directory, 'Microsoft\Edge\Application\msedge.exe')) }
        catch { }
        if ($edge) { return $edge }
    }
    foreach ($candidate in @(Get-EdgeRegistryValues 'Software\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe' '')) {
        $edge = Resolve-EdgeExecutable $candidate
        if ($edge) { return $edge }
    }
    throw 'EDGE_MISSING'
}

function Assert-EdgeIsolationPolicy {
    $path = 'Software\Policies\Microsoft\Edge'
    # Never bypass enterprise policy or let UserDataDir replace our fresh profile.
    foreach ($value in @(Get-EdgeRegistryValues $path 'UserDataDir' $true)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$value)) { throw 'EDGE_POLICY' }
    }
    foreach ($value in @(Get-EdgeRegistryValues $path 'RemoteDebuggingAllowed' $true)) {
        if ([string]$value -eq '0') { throw 'EDGE_POLICY' }
    }
    foreach ($value in @(Get-EdgeRegistryValues $path 'DeveloperToolsAvailability' $true)) {
        if ([string]$value -eq '2') { throw 'EDGE_POLICY' }
    }
}

function Get-WebHelperError([string]$Message, [string]$Fallback = 'WEB_FAILED') {
    if ($Message -in @('EDGE_MISSING', 'EDGE_POLICY', 'EDGE_LAUNCH', 'EDGE_EARLY_EXIT', 'EDGE_CDP', 'EDGE_START_TIMEOUT', 'EDGE_PROFILE')) {
        return $Message
    }
    if ($Message -in @('CDP write timeout', 'CDP read timeout', 'CDP response timeout', 'Invalid CDP response', 'CDP request failed')) {
        return 'EDGE_CDP'
    }
    return $Fallback
}

function Connect-IsolatedEdge {
    try { Send-Cdp 'Browser.getVersion' @{} $null 25000 | Out-Null }
    catch {
        if ($script:Browser.HasExited) { throw 'EDGE_EARLY_EXIT' }
        if ($_.Exception.Message -match '^CDP (write|read|response) timeout$') { throw 'EDGE_START_TIMEOUT' }
        throw 'EDGE_CDP'
    }
    $script:BrowserReady = $true
}

function Start-IsolatedEdge($Root, [bool]$Headless, [string]$AppUrl = 'https://music.163.com/') {
    $script:BrowserReady = $false
    $profile = Join-Path $Root 'profile'
    if (Test-Path -LiteralPath $profile) { throw 'EDGE_PROFILE' }
    $edge = Find-EdgeExecutable
    Assert-EdgeIsolationPolicy
    try {
        New-Item -ItemType Directory -Path (Join-Path $profile 'Default') -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $profile 'Default\Preferences'), '{"credentials_enable_service":false,"profile":{"password_manager_enabled":false}}')
    } catch { throw 'EDGE_PROFILE' }
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
    else { $start.Arguments += '--app=' + $AppUrl }
    try { $script:Browser = [Diagnostics.Process]::Start($start) }
    catch { throw 'EDGE_LAUNCH' }
    if (-not $script:Browser) { throw 'EDGE_LAUNCH' }
    $script:PipeInput.DisposeLocalCopyOfClientHandle()
    $script:PipeOutput.DisposeLocalCopyOfClientHandle()
    $script:DiscardOutput = $script:Browser.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
    $script:DiscardError = $script:Browser.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
    $script:Sequence = 0
    $script:Pending = [byte[]]::new(0)
    Connect-IsolatedEdge
}

function Stop-IsolatedEdge {
    if ($script:BrowserReady -and $script:PipeInput -and $script:Browser -and -not $script:Browser.HasExited) {
        try { Send-Cdp 'Browser.close' @{} $null 2000 | Out-Null } catch { }
    }
    if ($script:Browser) {
        try { if (-not $script:Browser.WaitForExit(3000)) { $script:Browser.Kill() } } catch { }
        $script:Browser.Dispose()
    }
    if ($script:PipeInput) { $script:PipeInput.Dispose() }
    if ($script:PipeOutput) { $script:PipeOutput.Dispose() }
    $script:BrowserReady = $false
    $script:Browser = $null
    $script:PipeInput = $null
    $script:PipeOutput = $null
}

# Official createNEFingerprint/getToken mapping adapted from NeriPlayer's
# NeteaseYdDeviceTokenProvider (GPL-3.0-or-later). No downloaded script injection,
# fake device identifiers, automation concealment, or empty-token fallback.
function Get-NeteaseDeviceScript {
    return @'
(async function() {
    if (location.origin !== 'https://music.163.com') return {error: 'DEVICE_ORIGIN'};
    if (typeof createNEFingerprint !== 'function') return {pending: true};
    try {
        const instance = createNEFingerprint({appId: '9d0ef7e0905d422cba1ecf7e73d77e67', timeout: 6000});
        const result = await Promise.race([
            instance.getToken(),
            new Promise(resolve => setTimeout(() => resolve(null), 7000))
        ]);
        if (location.origin !== 'https://music.163.com' || !result ||
            typeof result.token !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(result.token)) return {error: 'DEVICE_TOKEN'};
        return {token: result.token, userAgent: navigator.userAgent};
    } catch (_) { return {error: 'DEVICE_FAILED'}; }
})()
'@
}

function Invoke-NeteaseDeviceSnapshot {
    $root = [Console]::ReadLine()
    $result = @{ error = 'DEVICE_FAILED' }
    try {
        if (-not [IO.Path]::IsPathRooted($root) -or -not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Invalid workspace' }
        Start-IsolatedEdge $root $true
        $page = (Send-Cdp 'Target.getTargets' @{}).targetInfos | Where-Object { $_.type -eq 'page' -and $_.url -eq 'about:blank' } | Select-Object -First 1
        if (-not $page) { throw 'Invalid initial page' }
        $session = (Send-Cdp 'Target.attachToTarget' @{ targetId = $page.targetId; flatten = $true }).sessionId
        Send-Cdp 'Page.navigate' @{ url = 'https://music.163.com/' } $session | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(25)
        while ([DateTime]::UtcNow -lt $deadline) {
            $evaluation = Send-Cdp 'Runtime.evaluate' @{ expression = (Get-NeteaseDeviceScript); returnByValue = $true; awaitPromise = $true } $session
            if ($evaluation.exceptionDetails -or -not $evaluation.result.value) { throw 'Invalid device response' }
            $value = $evaluation.result.value
            if ($value.pending) { Start-Sleep -Milliseconds 400; continue }
            if ($value.error -or -not $value.token) { throw 'Device unavailable' }
            $cookies = @((Send-Cdp 'Network.getCookies' @{ urls = @('https://music.163.com/') } $session).cookies)
            if ($cookies.Count -gt 64 -or ($cookies | Where-Object { $_.name -eq 'MUSIC_U' })) { throw 'Unexpected account context' }
            $deviceCookies = @($cookies | Where-Object { $_.name -eq 'sDeviceId' -and $_.value.Length -gt 0 })
            if ($deviceCookies.Count -ne 1) { throw 'Device cookie unavailable' }
            $result = @{ token = $value.token; deviceId = $deviceCookies[0].value; userAgent = $value.userAgent; cookies = $cookies }
            break
        }
    } catch { $result = @{ error = (Get-WebHelperError $_.Exception.Message 'DEVICE_FAILED') } }
    finally { Stop-IsolatedEdge }
    # Only the parent pipe receives the context. Profile, token and cookies are not cached.
    [Console]::WriteLine(($result | ConvertTo-Json -Depth 12 -Compress))
}

function Invoke-NeteaseWebLogin {
    Invoke-OfficialWebLogin 'https://music.163.com/' 'netease'
}

function Invoke-QqMusicWebLogin {
    Invoke-OfficialWebLogin 'https://y.qq.com/' 'qqmusic'
}

function Get-QqMusicCookies {
    # This context is our fresh profile only. Parent QQ cookies are observed by name,
    # never flattened or forwarded wholesale to the music API.
    $names = @('qqmusic_key','qm_keyst','musickey','qqmusic_uin','uin','wxuin',
        'tmeLoginType','login_type','euin','qqmusic_privilege','psrf_musickey_createtime',
        'psrf_access_token_expiresAt','psrf_qqopenid','psrf_qqunionid','psrf_qqaccess_token','wxopenid','wxunionid')
    return @((Send-Cdp 'Storage.getCookies' @{}).cookies | Where-Object {
        $domain = $_.domain.TrimStart('.').ToLowerInvariant()
        $_.name -in $names -and ($domain -in @('qq.com','music.qq.com','y.qq.com') -or $domain.EndsWith('.y.qq.com'))
    })
}

function Test-QqMusicWebReady($Cookies) {
    $key = [bool]($Cookies | Where-Object { $_.name -in @('qqmusic_key','qm_keyst','musickey') -and $_.value.Length -gt 0 })
    $identity = [bool]($Cookies | Where-Object { $_.name -in @('qqmusic_uin','uin','wxuin') -and $_.value -match '^(o0*)?[1-9][0-9]{0,18}$' })
    # Some official flows omit type; Java normalizes aliases and still requires
    # successful authenticated GetLoginUserInfo before importing an account.
    return $key -and $identity
}

function Invoke-OfficialWebLogin([string]$AppUrl, [string]$Platform) {
    $root = [Console]::ReadLine()
    $result = @{ error = 'WEB_FAILED' }
    try {
        if (-not [IO.Path]::IsPathRooted($root) -or -not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Invalid workspace' }
        Start-IsolatedEdge $root $false $AppUrl
        $originPattern = if ($Platform -eq 'qqmusic') { '^https://(y|music)\.qq\.com/' } else { '^https://music\.163\.com/' }
        $cookieUrls = if ($Platform -eq 'qqmusic') { @('https://y.qq.com/', 'https://u.y.qq.com/cgi-bin/musicu.fcg') } else {
            @('https://music.163.com/', 'https://music.163.com/weapi/w/nuser/account/get', 'https://interface.music.163.com/eapi/song/enhance/player/url/v1')
        }
        # Leave room for cold startup, the last CDP request and cleanup inside Java's 5-minute operation.
        $deadline = [DateTime]::UtcNow.AddSeconds(250)
        $targetId = $null
        $session = $null
        $stableCookies = $null
        while ([DateTime]::UtcNow -lt $deadline) {
            $pages = @((Send-Cdp 'Target.getTargets' @{}).targetInfos | Where-Object { $_.type -eq 'page' })
            if ($Platform -eq 'qqmusic') {
                # QQ's redirect/popup can replace the original target. Collect the
                # fresh profile's selected cookies, not just that first page session.
                $page = $pages | Where-Object { $_.url -match $originPattern } | Select-Object -First 1
                if ($pages.Count -eq 0) { $result = @{ error = 'WEB_CLOSED' }; break }
            } elseif ($targetId) {
                $page = $pages | Where-Object { $_.targetId -eq $targetId } | Select-Object -First 1
                if (-not $page) { $result = @{ error = 'WEB_CLOSED' }; break }
            } else {
                $page = $pages | Where-Object { $_.url -match $originPattern } | Select-Object -First 1
                if ($page) {
                    $targetId = $page.targetId
                    $session = (Send-Cdp 'Target.attachToTarget' @{ targetId = $targetId; flatten = $true }).sessionId
                }
            }
            if ($page -and $page.url -match $originPattern) {
                $cookies = if ($Platform -eq 'qqmusic') { @(Get-QqMusicCookies) } else { @((Send-Cdp 'Network.getCookies' @{ urls = $cookieUrls } $session).cookies | Where-Object {
                    $domain = $_.domain.TrimStart('.').ToLowerInvariant()
                    $domain -eq 'music.163.com' -or $domain.EndsWith('.music.163.com')
                }) }
                if ($cookies.Count -gt 64) { throw 'Cookie bound exceeded' }
                $credentialNames = if ($Platform -eq 'qqmusic') { @('qqmusic_key','qm_keyst','musickey') } else { @('MUSIC_U') }
                $ready = [bool]($cookies | Where-Object { $_.name -in $credentialNames -and $_.value.Length -gt 0 })
                if ($Platform -eq 'qqmusic') {
                    $ready = Test-QqMusicWebReady $cookies
                    $snapshot = $cookies | Sort-Object name,domain,path | ConvertTo-Json -Depth 12 -Compress
                    if (-not $ready -or $snapshot -ne $stableCookies) {
                        $stableCookies = if ($ready) { $snapshot } else { $null }
                        $ready = $false
                    }
                }
                if ($ready) {
                    $result = @{ cookies = $cookies }; break
                }
            }
            if ($script:Browser.HasExited) { $result = @{ error = 'WEB_CLOSED' }; break }
            Start-Sleep -Milliseconds 1000
        }
        if (-not $result.cookies -and $result.error -eq 'WEB_FAILED') { $result = @{ error = 'WEB_TIMEOUT' } }
    } catch {
        $result = @{ error = (Get-WebHelperError $_.Exception.Message) }
    } finally { Stop-IsolatedEdge }
    # Credentials travel only through the parent's anonymous pipe. Never log raw errors or cookies.
    [Console]::WriteLine(($result | ConvertTo-Json -Depth 12 -Compress))
}
