$root = [Console]::ReadLine()
$script:Checks = 0
function Assert-Fixture([bool]$Value) {
    $script:Checks++
    if (-not $Value) { throw ('Fixture assertion ' + $script:Checks) }
}
function Assert-FixtureError($Action, [string]$Expected) {
    $message = ''
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    Assert-Fixture ($message -eq $Expected)
}

# These overrides exist only in this helper process. No real registry is changed.
${env:ProgramFiles(x86)} = $null
$env:ProgramFiles = $null
$env:ProgramW6432 = $null
$env:LOCALAPPDATA = $null
$directories = @(Get-EdgeInstallDirectories)
Assert-Fixture ($directories.Count -ge 3)
$originalDirectories = ${function:Get-EdgeInstallDirectories}
$originalRegistry = ${function:Get-EdgeRegistryValues}
$script:FixtureDirectories = @()
$script:FixtureRegistry = @()
function Get-EdgeInstallDirectories { $script:FixtureDirectories }
function Get-EdgeRegistryValues($SubKey, $ValueName, $Required = $false) {
    if ($SubKey -eq 'Software\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe') {
        Assert-Fixture ($ValueName -eq '')
        $script:FixtureRegistry
    } elseif ($ValueName -eq $script:FixturePolicyName) {
        if ($script:FixtureRegistryDenied) { throw 'EDGE_POLICY' }
        $script:FixturePolicyValue
    }
}
try {
    $custom = Join-Path $root ('Custom space ' + [char]0x6d4b + [char]0x8bd5 + " quote'")
    New-Item -ItemType Directory -Path $custom | Out-Null
    $executable = Join-Path $custom 'msedge.exe'
    [IO.File]::WriteAllBytes($executable, [byte[]]::new(0))
    Assert-Fixture ((Resolve-EdgeExecutable $executable) -eq $executable)
    Assert-Fixture ((Resolve-EdgeExecutable ('"' + $executable + '"')) -eq $executable)
    $env:SALT_EDGE_FIXTURE_PATH = $custom
    Assert-Fixture ((Resolve-EdgeExecutable '%SALT_EDGE_FIXTURE_PATH%\msedge.exe') -eq $executable)
    foreach ($invalid in @($null, '', 'msedge.exe', 'C:msedge.exe', '\msedge.exe', ($executable + ' --app=https://y.qq.com/'),
        ('"' + $executable + '" --flag'), (Join-Path $custom 'not-edge.exe'), (Join-Path $custom 'missing\msedge.exe'),
        ($executable + [char]10), '"C:\missing\msedge.exe')) {
        Assert-Fixture (-not (Resolve-EdgeExecutable $invalid))
    }
    $script:FixtureRegistry = @('missing', ('"' + $executable + '" --flag'), ('"' + $executable + '"'))
    Assert-Fixture ((Find-EdgeExecutable) -eq $executable)
    $standard = Join-Path $root 'Synthetic local install'
    $standardExe = Join-Path $standard 'Microsoft\Edge\Application\msedge.exe'
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($standardExe)) -Force | Out-Null
    [IO.File]::WriteAllBytes($standardExe, [byte[]]::new(0))
    $script:FixtureDirectories = @($null, '', $standard)
    Assert-Fixture ((Find-EdgeExecutable) -eq $standardExe)
    $script:FixtureDirectories = @($null, '')
    $script:FixtureRegistry = @('missing', ($executable + ' --flag'))
    Assert-FixtureError { Find-EdgeExecutable } 'EDGE_MISSING'

    foreach ($policy in @(
        @{ Name = 'UserDataDir'; Value = 'C:\not-our-profile' },
        @{ Name = 'RemoteDebuggingAllowed'; Value = 0 },
        @{ Name = 'DeveloperToolsAvailability'; Value = 2 }
    )) {
        $script:FixturePolicyName = $policy.Name
        $script:FixturePolicyValue = $policy.Value
        Assert-FixtureError { Assert-EdgeIsolationPolicy } 'EDGE_POLICY'
    }
    $script:FixturePolicyName = 'RemoteDebuggingAllowed'
    $script:FixturePolicyValue = 1
    Assert-EdgeIsolationPolicy
    Assert-Fixture $true
    $script:FixtureRegistryDenied = $true
    Assert-FixtureError { Assert-EdgeIsolationPolicy } 'EDGE_POLICY'
    $script:FixtureRegistryDenied = $false
    $script:FixturePolicyName = $null
    $script:FixtureRegistry = @($executable)

    # The empty fixture binary cannot run: verify the fixed safe launch diagnostic.
    $launch = Join-Path $root 'launch'
    New-Item -ItemType Directory -Path $launch | Out-Null
    try { Assert-FixtureError { Start-IsolatedEdge $launch $true } 'EDGE_LAUNCH' }
    finally { Stop-IsolatedEdge }
    Assert-FixtureError { Start-IsolatedEdge $launch $true } 'EDGE_PROFILE'
    foreach ($errorCode in @('EDGE_MISSING', 'EDGE_POLICY', 'EDGE_LAUNCH', 'EDGE_EARLY_EXIT',
        'EDGE_CDP', 'EDGE_START_TIMEOUT', 'EDGE_PROFILE')) {
        Assert-Fixture ((Get-WebHelperError $errorCode) -eq $errorCode)
    }
    Assert-Fixture ((Get-WebHelperError 'SYNTHETIC_SECRET') -eq 'WEB_FAILED')
    Assert-Fixture ((Get-WebHelperError 'SYNTHETIC_SECRET' 'DEVICE_FAILED') -eq 'DEVICE_FAILED')
    Assert-Fixture ((Get-WebHelperError 'CDP request failed') -eq 'EDGE_CDP')
    $originalCdp = ${function:Send-Cdp}
    function Send-Cdp($Method, $Parameters, $Session = $null, $TimeoutMillis = 8000) {
        Assert-Fixture ($Method -eq 'Browser.getVersion' -and $TimeoutMillis -eq 25000)
        if ($script:FixtureCdpError) { throw $script:FixtureCdpError }
        return @{ product = 'Synthetic' }
    }
    try {
        $script:Browser = [pscustomobject]@{ HasExited = $true }
        $script:FixtureCdpError = 'Invalid CDP response'
        Assert-FixtureError { Connect-IsolatedEdge } 'EDGE_EARLY_EXIT'
        $script:Browser.HasExited = $false
        Assert-FixtureError { Connect-IsolatedEdge } 'EDGE_CDP'
        $script:FixtureCdpError = 'CDP read timeout'
        Assert-FixtureError { Connect-IsolatedEdge } 'EDGE_START_TIMEOUT'
        $script:FixtureCdpError = 'SYNTHETIC_SECRET'
        Assert-FixtureError { Connect-IsolatedEdge } 'EDGE_CDP'
        $script:FixtureCdpError = $null
        Connect-IsolatedEdge
        Assert-Fixture $script:BrowserReady
    } finally {
        ${function:Send-Cdp} = $originalCdp
        $script:Browser = $null
        $script:BrowserReady = $false
    }
    [Console]::WriteLine((@{ passed = $true; checks = $script:Checks } | ConvertTo-Json -Compress))
} finally {
    ${function:Get-EdgeInstallDirectories} = $originalDirectories
    ${function:Get-EdgeRegistryValues} = $originalRegistry
    Stop-IsolatedEdge
}
