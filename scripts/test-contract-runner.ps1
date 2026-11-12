param([Parameter(Mandatory = $true)][string]$JdkHome)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $root ('plugin/build/contract-runner/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$tests = $classes
$temporaryFiles = Join-Path $build 'tmp'
$hostProfile = Join-Path $build 'profile'
$java = Join-Path $JdkHome 'bin/java.exe'
$ContractTimeoutSeconds = 2
New-Item -ItemType Directory -Path $classes, $temporaryFiles, $hostProfile -Force | Out-Null
$fixture = @'
class PassContract {
    public static void main(String[] args) {
        if (!System.getProperty("user.home").equals(System.getenv("APPDATA")) ||
            !System.getenv("APPDATA").equals(System.getenv("LOCALAPPDATA"))) throw new AssertionError("Profile isolation");
        System.out.println("PASS: isolated profile");
    }
}
class SlowContract {
    public static void main(String[] args) throws Exception {
        System.out.println("started"); System.out.flush();
        System.err.println("waiting"); System.err.flush();
        Thread.sleep(10000);
    }
}
class FailContract { public static void main(String[] args) { System.exit(7); } }
class BackgroundContract {
    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(() -> { throw new AssertionError("background failure"); });
        worker.start(); worker.join();
    }
}
class FloodContract {
    public static void main(String[] args) throws Exception {
        Thread errors = new Thread(() -> { for (int i=0;i<2000;i++) System.err.println("stderr "+"x".repeat(100)); });
        errors.start();
        for (int i=0;i<2000;i++) System.out.println("stdout "+"x".repeat(100));
        errors.join();
    }
}
'@
$source = Join-Path $build 'RunnerFixtures.java'
[IO.File]::WriteAllText($source, $fixture, [Text.UTF8Encoding]::new($false))
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'Contract runner fixture compilation failed.' }
# Exercise the production function without compiling the proprietary host or duplicating its logic.
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'build-local.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Build script syntax errors.' }
$function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Contract' }, $true)
if (-not $function) { throw 'Production contract runner function not found.' }
Invoke-Expression $function.Extent.Text
Invoke-Contract -Test PassContract -BaseOnly | Out-Null
Invoke-Contract -Test FloodContract -BaseOnly | Out-Null
foreach ($test in @('FailContract', 'BackgroundContract', 'SlowContract')) {
    $failure = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try { Invoke-Contract -Test $test -BaseOnly | Out-Null } catch { $failure = $_.Exception.Message }
    if (-not $failure) { throw "Runner accepted a failed contract: $test" }
    if ($test -eq 'SlowContract') {
        if ($failure -notlike '*timed out*' -or $watch.Elapsed.TotalSeconds -gt 6) { throw "Timeout did not stop the fixture: $failure" }
        $log = Get-Content -LiteralPath (Join-Path $build "$test.base.log") -Raw
        if ($log -notmatch 'started' -or $log -notmatch 'waiting') { throw 'Timeout lost stdout/stderr diagnostics.' }
        $owned = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$classes*SlowContract*" }
        if ($owned) { throw 'Timed-out contract process is still alive.' }
    } elseif ($failure -notlike '*Plugin contract failed*') { throw "Unexpected runner error: $failure" }
}
Write-Output 'PASS: contract timeout and process termination, concurrent output draining, nonzero/background failure, and isolated host profile'
