# Salt Extend wrapper for the GPL-3.0 MineRadio Apple Music UIA/SMTC engine.
# Input is UTF-8 JSON over stdin, never command text.
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
. (Join-Path $PSScriptRoot 'lib/am-common.ps1')
. (Join-Path $PSScriptRoot 'lib/am-smtc.ps1')
. (Join-Path $PSScriptRoot 'lib/am-uia.ps1')
. (Join-Path $PSScriptRoot 'lib/am-play.ps1')
try {
    $inputData = [Console]::In.ReadLine() | ConvertFrom-Json
    if ($inputData.id -notmatch '^[a-z]{2}:[1-9][0-9]{0,18}$' -or
        [string]::IsNullOrWhiteSpace($inputData.title) -or $inputData.title.Length -gt 512 -or $inputData.artist.Length -gt 512) {
        throw 'INPUT'
    }
    $parts = $inputData.id.Split(':')
    $url = 'https://music.apple.com/' + $parts[0] + '/song/song/' + $parts[1]
    $result = Invoke-AmPlaySong -Title $inputData.title -Artist $inputData.artist -Url $url -Retries 0 -TimeoutMs 6000 -PageWaitMs 12000
    [Console]::WriteLine((@{ ok = [bool]$result.ok; stage = [string]$result.stage } | ConvertTo-Json -Compress))
} catch {
    [Console]::WriteLine('{"ok":false,"stage":"HELPER_FAILED"}')
}
