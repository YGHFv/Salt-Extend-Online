$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../plugin/src/main/resources/applemusic/lib/am-uia.ps1')
$script:checks = 0
function Assert($value) { $script:checks++; if (-not $value) { throw "Apple launcher check $script:checks" } }
# Replace OS discovery and launch for deterministic tests; no application is started.
function Get-AmProcess { return $null }
function Get-AmLaunchTarget { return $null }
$result = Ensure-AmRunning 1
Assert ($result.stage -eq 'APP_NOT_INSTALLED')
$script:launches = @()
function Start-Process {
    param($WindowStyle, $FilePath, $ArgumentList)
    $script:launches += @{ style = $WindowStyle; file = $FilePath; args = $ArgumentList }
}
Start-AmInstalled @{ kind = 'aumid'; target = 'AppleInc.AppleMusicWin_fixture!Music' }
Assert ($script:launches[0].style -eq 'Hidden')
Assert ($script:launches[0].file -eq (Join-Path $env:SystemRoot 'explorer.exe'))
Assert ($script:launches[0].args -eq 'shell:AppsFolder\AppleInc.AppleMusicWin_fixture!Music')
function Get-AmLaunchTarget { return @{ kind = 'alias'; target = 'C:\fixture\AppleMusic.exe' } }
$result = Invoke-AmNavigateUrl 'https://music.apple.com/cn/song/song/12345'
Assert ($result.ok -and $result.method -eq 'AppleMusic.exe /url')
Assert ($script:launches[-1].args -eq '/url "https://music.apple.com/cn/song/song/12345"')
function Get-AmLaunchTarget { return @{ kind = 'aumid'; target = 'AppleInc.AppleMusicWin_fixture!Music' } }
$result = Invoke-AmNavigateUrl 'https://music.apple.com/cn/song/song/12345'
Assert ($result.ok -and $script:launches[-1].file -eq 'music://music.apple.com/cn/song/song/12345')
Assert (-not (Invoke-AmNavigateUrl 'https://evil.invalid/123').ok)
function Start-AmInstalled { throw 'fixture failure' }
$result = Ensure-AmRunning 1
Assert ($result.stage -eq 'APP_LAUNCH_FAILED')
Write-Output "PASS: $script:checks Apple launch/AUMID/hidden/deeplink/invalid-target fixtures; no application launched"
