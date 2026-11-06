param([string]$ToolDirectory = '')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $ToolDirectory) { $ToolDirectory = Join-Path $root '.tools/kotlin-2.4.0' }
New-Item -ItemType Directory -Path $ToolDirectory -Force | Out-Null
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'kotlin-ui-toolchain.json') -Raw | ConvertFrom-Json
foreach ($item in $lock) {
    if ($item.file -notmatch '^[a-zA-Z0-9.\-]+\.jar$' -or -not ($item.url.StartsWith('https://repo.maven.apache.org/maven2/') -or $item.url.StartsWith('https://dl.google.com/dl/android/maven2/'))) {
        throw 'Invalid compiler toolchain entry.'
    }
    $target = Join-Path $ToolDirectory $item.file
    if (-not (Test-Path -LiteralPath $target)) { Invoke-WebRequest -Uri $item.url -OutFile $target }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256) {
        throw "Compiler checksum mismatch: $($item.file)"
    }
}
Write-Output 'Verified Kotlin/Compose compiler 2.4.0. Build tools only; not bundled in the plugin.'
