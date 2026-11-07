$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../plugin/src/main/resources/auth/netease-web-login.ps1')
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../plugin/build'))
$root = Join-Path $workspace ('edge-fixture-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root -Force | Out-Null
try {
    Start-IsolatedEdge $root $true
    $target = (Send-Cdp 'Target.getTargets' @{}).targetInfos | Where-Object { $_.type -eq 'page' } | Select-Object -First 1
    if ($target.url -ne 'about:blank') { throw 'Fixture browser visited a real website' }
    $session = (Send-Cdp 'Target.attachToTarget' @{ targetId = $target.targetId; flatten = $true }).sessionId
    Send-Cdp 'Network.setCookies' @{ cookies = @(
        @{ name = 'MUSIC_U'; value = 'SYNTHETIC_SECRET'; domain = '.music.163.com'; path = '/'; secure = $true; httpOnly = $true },
        @{ name = 'OTHER'; value = 'NOT_IMPORTED'; domain = '.example.invalid'; path = '/' }
    ) } $session | Out-Null
    $cookies = @((Send-Cdp 'Network.getCookies' @{ urls = @('https://music.163.com/', 'https://interface.music.163.com/eapi/song/enhance/player/url/v1') } $session).cookies)
    if ($cookies.Count -ne 1 -or $cookies[0].value -ne 'SYNTHETIC_SECRET' -or -not $cookies[0].httpOnly) { throw 'Scoped HttpOnly cookie transport failed' }
    if (Test-Path -LiteralPath (Join-Path $root 'profile/DevToolsActivePort')) { throw 'Unexpected debugging port' }
    Write-Output 'PASS: isolated installed Edge + anonymous-pipe CDP + synthetic HttpOnly cookie scope; no debugging port, website, account or screenshot.'
} finally {
    Stop-IsolatedEdge
    $resolved = [IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe cleanup target' }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try { Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop; break }
        catch { if ($attempt -eq 19) { throw }; Start-Sleep -Milliseconds 100 }
    }
}
