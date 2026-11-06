param([string]$JdkHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    $compiler = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $compiler) { throw 'Set JAVA_HOME or pass -JdkHome with JDK 17 or newer.' }
    $JdkHome = Split-Path (Split-Path $compiler.Source -Parent) -Parent
}
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javac)) { throw "JDK compiler not found: $javac" }
$output = Join-Path $root 'core/build/contract-classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'core/src/main/java'), (Join-Path $root 'core/src/test/java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javac --release 17 -encoding UTF-8 -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed. Use JDK 17 or newer.' }
& $java -cp $output io.github.yghfv.saltneri.core.CoreContractTest
if ($LASTEXITCODE -ne 0) { throw 'Core contract tests failed.' }
