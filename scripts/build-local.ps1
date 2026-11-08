param(
    [Parameter(Mandatory = $true)][string]$SaltHome,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [switch]$Experimental,
    [string]$BilibiliProbe = '',
    [string]$NeteaseProbe = '',
    [string]$LoginProbe = ''
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& python (Join-Path $root 'scripts/verify-project.py')
if ($LASTEXITCODE -ne 0) { throw 'Project metadata or contract matrix validation failed.' }
& python (Join-Path $root 'scripts/test-inspector.py')
if ($LASTEXITCODE -ne 0) { throw 'Class inspector tests failed.' }
& (Join-Path $PSScriptRoot 'test-core.ps1') -JdkHome $JdkHome
$pluginMetadata = Get-Content -LiteralPath (Join-Path $root 'plugin.properties') -Raw | ConvertFrom-StringData
& python (Join-Path $root 'scripts/verify-vendor.py')
if ($LASTEXITCODE -ne 0) { throw 'Vendored QR source verification failed.' }
$salt = [System.IO.Path]::GetFullPath($SaltHome)
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javac)) { throw "JDK compiler not found: $javac" }
$compilerVersion = & $javac -version 2>&1
if ($LASTEXITCODE -ne 0 -or "$compilerVersion" -notmatch '^javac (\d+)' -or [int]$Matches[1] -lt 25) {
    throw 'Local host build requires JDK 25 or newer.'
}
$configs = @(Get-ChildItem -LiteralPath (Join-Path $salt 'app') -Filter '*.cfg')
if ($configs.Count -ne 1) { throw 'Expected one Salt application configuration.' }
$config = Get-Content -LiteralPath $configs[0].FullName -Raw
if ($config -notmatch '(?m)^app\.classpath=\$APPDIR[\\/](.+)\r?$') { throw 'Missing host classpath.' }
$hostPackage = [System.IO.Path]::GetFullPath((Join-Path (Join-Path $salt 'app') $Matches[1].Trim()))
$appRoot = [System.IO.Path]::GetFullPath((Join-Path $salt 'app')) + [System.IO.Path]::DirectorySeparatorChar
if (-not $hostPackage.StartsWith($appRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Host package is outside app directory.' }
if (-not (Test-Path -LiteralPath $hostPackage)) { throw 'Host classpath package not found.' }

# A fresh build directory avoids packaging stale classes without deleting anything.
$build = Join-Path $root ('plugin/build/local/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$tests = Join-Path $build 'test-classes'
New-Item -ItemType Directory -Path $classes, $tests -Force | Out-Null
$sourceRoots = @('core/src/main/java', 'plugin/src/main/java') | ForEach-Object { Join-Path $root $_ }
$sources = @(Get-ChildItem -LiteralPath $sourceRoots -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javac --release 25 -encoding UTF-8 -proc:none -classpath $hostPackage -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Local plugin compilation failed.' }
& (Join-Path $PSScriptRoot 'build-native-ui.ps1') -HostPackage $hostPackage -JdkHome $JdkHome -Classes $classes
$configName = if ($Experimental) { 'preference_config_experimental.json' } else { 'preference_config.json' }
Copy-Item -LiteralPath (Join-Path $root "plugin/src/main/resources/$configName") -Destination (Join-Path $classes 'preference_config.json')
Copy-Item -LiteralPath (Join-Path $root 'plugin/src/main/resources/host-profiles') -Destination $classes -Recurse
Copy-Item -LiteralPath (Join-Path $root 'plugin/src/main/resources/auth') -Destination $classes -Recurse
$licenseDirectory = Join-Path $classes 'META-INF/licenses'
New-Item -ItemType Directory -Path $licenseDirectory -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'plugin/src/main/resources/META-INF/extensions.idx') -Destination (Join-Path $classes 'META-INF/extensions.idx')
Copy-Item -LiteralPath (Join-Path $root 'LICENSE'), (Join-Path $root 'THIRD_PARTY_NOTICES.md') -Destination $licenseDirectory
Copy-Item -LiteralPath (Join-Path $root 'licenses/QR-Code-generator-MIT.txt') -Destination $licenseDirectory
Copy-Item -LiteralPath (Join-Path $root 'licenses/Kotlin-Compose-Apache-2.0.txt') -Destination $licenseDirectory
Copy-Item -LiteralPath (Join-Path $root 'licenses/Simple-Icons-CC0.txt') -Destination $licenseDirectory
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'plugin/src/test/java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javac --release 25 -encoding UTF-8 -proc:none -classpath "$classes;$hostPackage" -d $tests @testSources
if ($LASTEXITCODE -ne 0) { throw 'Host smoke test compilation failed.' }
$temporaryFiles = Join-Path $build 'tmp'
New-Item -ItemType Directory -Path $temporaryFiles -Force | Out-Null
function Invoke-Contract([string]$Test, [switch]$BaseOnly) {
    $runtime = if ($BaseOnly) { 'base' } else { 'host' }
    $log = Join-Path $build "$Test.$runtime.log"
    $arguments = @('-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$temporaryFiles")
    if ($BaseOnly) { $arguments += @('--limit-modules=java.base', '-classpath', "$tests;$classes") }
    else { $arguments += @('--enable-native-access=ALL-UNNAMED', "-Dskiko.library.path=$(Join-Path $salt 'app')", '-classpath', "$tests;$classes;$hostPackage") }
    & $java @arguments $Test 2>&1 | Tee-Object -FilePath $log
    # Background coroutine/thread failures can otherwise leave a successful process exit code.
    if ($LASTEXITCODE -ne 0 -or (Select-String -LiteralPath $log -Pattern 'ComposeRuntimeError|AssertionError|Exception in thread|Traceback|^FAIL' -Quiet)) {
        throw "Plugin contract failed ($runtime): $Test; see $log"
    }
}
$testMatrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'contract-tests.json') -Raw | ConvertFrom-Json
foreach ($entry in $testMatrix.tests) {
    Invoke-Contract -Test $entry.class
    if ($entry.baseOnly) { Invoke-Contract -Test $entry.class -BaseOnly }
}

$meta = Join-Path $classes 'META-INF'
New-Item -ItemType Directory -Path $meta -Force | Out-Null
$pluginClass = if ($Experimental) { 'ExperimentalPlugin' } else { 'MainPlugin' }
$pluginVersion = if ($Experimental) { $pluginMetadata.version } else { $pluginMetadata.passiveVersion }
$pluginName = $pluginMetadata.name
$pluginDescription = if ($Experimental) { 'Native Salt sidebar/catalog/QR UI and explicit playback preview; unverified.' } else { 'Passive Steam 1.19.3 host inspection; no online playback.' }
$permissionLine = if ($Experimental) { "Plugin-Permissions: class-transform`n" } else { '' }
$manifest = @"
Manifest-Version: 1.0
Plugin-Class: io.github.yghfv.saltneri.plugin.$pluginClass
Plugin-Id: $($pluginMetadata.id)
Plugin-Version: $pluginVersion
Plugin-Provider: $($pluginMetadata.provider)
Plugin-Name: $pluginName
Plugin-Open-Source-Url: $($pluginMetadata.openSourceUrl)
Plugin-Description: $pluginDescription
Plugin-Has-Config: true
$permissionLine
"@
$manifestLines = [System.Collections.Generic.List[string]]::new()
foreach ($line in ($manifest -split '\r?\n')) {
    $remaining = $line
    while ($remaining.Length -gt 72) {
        $manifestLines.Add($remaining.Substring(0, 72))
        $remaining = ' ' + $remaining.Substring(72)
    }
    if ($remaining.Length -gt 0) { $manifestLines.Add($remaining) }
}
[System.IO.File]::WriteAllText((Join-Path $meta 'MANIFEST.MF'), [string]::Join("`r`n", $manifestLines) + "`r`n`r`n", [System.Text.Encoding]::ASCII)
$destination = Join-Path $root 'plugin/build/libs'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$artifact = Join-Path $destination "plugin-$($pluginMetadata.id)-$pluginVersion.spmod"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporary = Join-Path $build 'hostprobe.zip'
# Only classes/resources go into the artifact, never the proprietary host package.
$zip = [System.IO.Compression.ZipFile]::Open($temporary, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $classes -File -Recurse) {
        $relative = $file.FullName.Substring($classes.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, 'classes/' + $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Copy-Item -LiteralPath $temporary -Destination $artifact -Force
$validationArgs = @((Join-Path $root 'scripts/validate-artifact.py'), $artifact)
if ($Experimental) { $validationArgs += '--experimental' }
& python @validationArgs
if ($LASTEXITCODE -ne 0) { throw 'Artifact isolation or opt-in configuration validation failed.' }
Write-Output "Built $pluginName`: $artifact"
Write-Output 'No files in the Salt/Steam installation or user data were modified.'
if ($BilibiliProbe) {
    Write-Output 'Explicit live probe: anonymous API and one-byte CDN request only; no Salt host on classpath.'
    & $java '--limit-modules=java.base' -classpath "$tests;$classes" io.github.yghfv.saltneri.plugin.bilibili.BiliLiveProbe '--explicit-public-probe' $BilibiliProbe
    if ($LASTEXITCODE -ne 0) { throw 'Live Bilibili probe stopped; the separately validated offline artifact remains available.' }
}
if ($LoginProbe) {
    & $java '--limit-modules=java.base' -classpath "$tests;$classes" io.github.yghfv.saltneri.plugin.auth.AuthLiveProbe '--explicit-create-only' $LoginProbe.ToUpperInvariant()
    if ($LASTEXITCODE -ne 0) { throw 'Live QR creation probe stopped; no account confirmation was attempted.' }
}
if ($NeteaseProbe) {
    Write-Output 'Explicit live probe: anonymous NetEase API and one-byte CDN request only; no Salt host on classpath.'
    & $java '--limit-modules=java.base' -classpath "$tests;$classes" io.github.yghfv.saltneri.plugin.netease.NeteaseLiveProbe '--explicit-public-probe' $NeteaseProbe
    if ($LASTEXITCODE -ne 0) { throw 'Live NetEase probe stopped; the separately validated offline artifact remains available.' }
}
