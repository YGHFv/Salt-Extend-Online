param(
    [Parameter(Mandatory = $true)][string]$HostPackage,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$Classes
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tools = Join-Path $root '.tools/kotlin-2.4.0'
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'kotlin-ui-toolchain.json') -Raw | ConvertFrom-Json
$jars = foreach ($item in $lock) {
    $path = Join-Path $tools $item.file
    if (-not (Test-Path -LiteralPath $path)) { throw 'Run scripts/setup-native-ui.ps1 to prepare the pinned native UI compiler.' }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256) { throw 'Native UI compiler hash mismatch.' }
    $path
}
$java = Join-Path $JdkHome 'bin/java.exe'
$compose = Join-Path $tools 'kotlin-compose-compiler-plugin-embeddable-2.4.0.jar'
$compileOnly = @($lock | Where-Object { $_.compileOnly } | ForEach-Object { Join-Path $tools $_.file })
$compileClasspath = [string]::Join(';', $compileOnly + @($Classes, $HostPackage))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'plugin/src/main/kotlin') -Recurse -Filter '*.kt' | ForEach-Object { $_.FullName })
& $java '-Dfile.encoding=UTF-8' -classpath ([string]::Join(';', $jars)) org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 25 -module-name saltneri-native-ui -classpath $compileClasspath `
    "-Xplugin=$compose" -d $Classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Host-compatible native Compose UI compilation failed.' }
& python (Join-Path $PSScriptRoot 'verify-native-ui.py') $HostPackage $Classes
if ($LASTEXITCODE -ne 0) { throw 'Emitted native UI ABI does not match the actual host.' }
