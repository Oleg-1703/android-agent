param(
    [ValidateSet('build', 'start', 'stop', 'smoke', 'ui')][string]$Action = 'build',
    [string]$ToolsRoot = "$env:USERPROFILE/.local/share/android-agent-tools"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:ANDROID_HOME = Join-Path $ToolsRoot 'sdk'
$env:ANDROID_USER_HOME = Join-Path $ToolsRoot 'user'
$env:ANDROID_AVD_HOME = Join-Path $ToolsRoot 'avd'
$env:GRADLE_USER_HOME = Join-Path $ToolsRoot 'gradle'
$jdk = Get-ChildItem (Join-Path $ToolsRoot 'java') -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1
if (!$jdk) { throw 'JDK 17 missing. See docs/DEVELOPMENT_RU.md.' }
$env:JAVA_HOME = $jdk.FullName
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$runner = 'com.oleg.androidagent.test/com.oleg.androidagent.DeviceSmoke'
function Device([string[]]$Arguments) {
    & $adb -s emulator-5554 @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB command failed.' }
}
function Phase([string]$Name) {
    $result = Device -Arguments @('shell', 'am', 'instrument', '-r', '-w', '-e', 'phase', $Name, $runner)
    $result
    if (($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1' -or ($result -join "`n") -notmatch 'PASS:') { throw "Smoke phase failed: $Name" }
}
function ExportScreenshot([string]$Name, [string]$Directory) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $adb
    $info.Arguments = "-s emulator-5554 exec-out run-as com.oleg.androidagent cat cache/$Name.png"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($info)
    $file = [IO.File]::Create((Join-Path $Directory "$Name.png"))
    try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Screenshot export failed: $Name" }
    $process.Dispose()
}
switch ($Action) {
    'build' {
        # Gradle test classpaths fail under some non-ASCII Windows paths; use one junction, not another checkout.
        $buildPath = Join-Path $ToolsRoot 'project'
        if (!(Test-Path -LiteralPath $buildPath)) { New-Item -ItemType Junction -Path $buildPath -Target $repo | Out-Null }
        $link = Get-Item -LiteralPath $buildPath
        if (!$link.Target -or [IO.Path]::GetFullPath($link.Target[0]).TrimEnd('\') -ne [IO.Path]::GetFullPath($repo).TrimEnd('\')) { throw 'Build junction points to a different project.' }
        Push-Location -LiteralPath $buildPath
        try {
            & "$env:JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' -classpath 'gradle/wrapper/gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
            if ($LASTEXITCODE -ne 0) { throw 'Gradle checks failed.' }
        } finally { Pop-Location }
    }
    'start' {
        $devices = & $adb devices
        if (($devices -join "`n") -match 'emulator-5554\s') { throw 'Port 5554 is already in use. Existing emulator left untouched.' }
        $p = Start-Process -FilePath (Join-Path $env:ANDROID_HOME 'emulator/emulator.exe') -ArgumentList '-avd Oleg_Agent_API30 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 1024 -cores 2 -camera-back none -camera-front none -port 5554' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $ToolsRoot 'emulator.stdout.log') -RedirectStandardError (Join-Path $ToolsRoot 'emulator.stderr.log') -PassThru
        Write-Output "Emulator started, PID $($p.Id)."
    }
    default {
        $qemu = Device -Arguments @('shell', 'getprop', 'ro.kernel.qemu')
        if (($qemu -join '').Trim() -ne '1') { throw 'Disposable emulator required.' }
        if ($Action -eq 'stop') { Device -Arguments @('emu', 'kill'); break }
        Device -Arguments @('install', '-r', '-t', (Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'))
        Device -Arguments @('install', '-r', '-t', (Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
        if ($Action -eq 'smoke') {
            Phase 'write'
            Device -Arguments @('shell', 'am', 'force-stop', 'com.oleg.androidagent')
            Phase 'read'
        }
        Phase 'ui'
        Phase 'pc-contract'
        Phase 'phone-mode-settings'
        Phase 'screen-lease'
        $evidence = Join-Path $repo '.local/qa'
        New-Item -ItemType Directory -Force $evidence | Out-Null
        'home', 'telegram', 'fatsecret', 'ai', 'help', 'diagnostics' | ForEach-Object { ExportScreenshot $_ $evidence }
    }
}
