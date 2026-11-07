param([string]$JdkHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) { throw 'Set JAVA_HOME or pass -JdkHome with JDK 25 or newer.' }
foreach ($script in @('verify-project.py', 'verify-vendor.py', 'test-inspector.py')) {
    & python (Join-Path $PSScriptRoot $script)
    if ($LASTEXITCODE -ne 0) { throw "Static verification failed: $script" }
}
& (Join-Path $PSScriptRoot 'test-core.ps1') -JdkHome $JdkHome
$matrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'contract-tests.json') -Raw | ConvertFrom-Json
$build = Join-Path $root ('plugin/build/offline/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$temporary = Join-Path $build 'tmp'
New-Item -ItemType Directory -Path $classes, $temporary -Force | Out-Null
$sourceRoot = Join-Path $root 'plugin/src/main/java/io/github/yghfv/saltneri/plugin'
$testRoot = Join-Path $root 'plugin/src/test/java'
$contracts = @($matrix.tests | Where-Object { $_.offline })
$sources = @($matrix.offlineSources | ForEach-Object { Join-Path $sourceRoot $_ })
$sources += @($contracts | ForEach-Object { Join-Path $testRoot ($_.class.Replace('.', '/') + '.java') })
& (Join-Path $JdkHome 'bin/javac.exe') --release 25 -encoding UTF-8 -proc:none -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Host-free compilation failed. JDK 25 is required.' }
foreach ($entry in $contracts) {
    & (Join-Path $JdkHome 'bin/java.exe') '--limit-modules=java.base' "-Djava.io.tmpdir=$temporary" -cp $classes $entry.class
    if ($LASTEXITCODE -ne 0) { throw "Host-free contract failed: $($entry.class)" }
}
Write-Output 'PASS: host-free suite; no Salt installation, credentials or external media traffic required.'
