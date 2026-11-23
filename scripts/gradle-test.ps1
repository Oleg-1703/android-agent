# Runs Gradle unit tests on an ASCII-path copy of the project (Gradle test classpaths fail under the non-ASCII repo path).
#   powershell -File scripts/gradle-test.ps1 [-Tests '*PcUrl*'] [-Task testDebugUnitTest] [-Keep]
# Output goes to a UTF-8 log (default: <ToolsRoot>/gradle-test.log) and the last lines are echoed. The copy is deleted afterwards.
# Runs in the foreground; from WSL start it with Bash run_in_background (a foreground call is cut at 2 minutes).
param(
    [string]$Tests = '',
    [string]$Task = 'testDebugUnitTest',
    [string]$ToolsRoot = "$env:USERPROFILE/.local/share/android-agent-tools",
    [string]$Log = '',
    [switch]$Keep
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$Log) { $Log = Join-Path $ToolsRoot 'gradle-test.log' }
$copy = Join-Path $env:USERPROFILE ('aa-build-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$env:ANDROID_HOME = Join-Path $ToolsRoot 'sdk'
$env:ANDROID_USER_HOME = Join-Path $ToolsRoot 'user'
$env:GRADLE_USER_HOME = Join-Path $ToolsRoot 'gradle'
$jdk = Get-ChildItem (Join-Path $ToolsRoot 'java') -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1
if (!$jdk) { throw 'JDK 17 missing. See docs/DEVELOPMENT_RU.md.' }
$env:JAVA_HOME = $jdk.FullName
New-Item -ItemType Directory -Path $copy | Out-Null
try {
    robocopy $repo $copy /E /XD .git .gradle build .claude /XF *.log /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw 'Copy failed.' }
    $arguments = @('-Dfile.encoding=UTF-8', '-classpath', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain', '--offline', '--no-daemon', '--max-workers=1',
        '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process', $Task)
    if ($Tests) { $arguments += @('--tests', $Tests) }
    Push-Location $copy
    try {
        $ErrorActionPreference = 'Continue'
        & "$env:JAVA_HOME/bin/java.exe" @arguments *>&1 | ForEach-Object { "$_" } | Out-File -FilePath $Log -Encoding utf8
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
    } finally { Pop-Location }
    if ($code -eq 0) {
        $total = 0; $failures = 0
        foreach ($file in (Get-ChildItem (Join-Path $copy 'app/build/test-results') -Recurse -Filter *.xml -ErrorAction SilentlyContinue)) {
            $suite = ([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite
            $total += [int]$suite.tests; $failures += [int]$suite.failures + [int]$suite.errors
        }
        Add-Content -LiteralPath $Log -Value "RESULT: tests=$total failures=$failures"
    }
    Get-Content -LiteralPath $Log -Tail 25
    Write-Output "Gradle exit code: $code. Log: $Log"
    if ($code -ne 0) { exit 1 }
} finally {
    if (!$Keep) { Remove-Item -LiteralPath $copy -Recurse -Force -ErrorAction SilentlyContinue }
}
