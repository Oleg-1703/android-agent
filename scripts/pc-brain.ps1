param(
    [ValidateSet('start', 'stop', 'status', 'pair', 'watchdog', 'install-autostart', 'uninstall-autostart', 'install-reports', 'uninstall-reports')][string]$Action = 'status',
    [string]$ToolsRoot = "$env:USERPROFILE/.local/share/android-agent-tools",
    [string]$Python = "$env:USERPROFILE/.local/bin/python3.14.exe",
    [switch]$NoOpen
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$scriptPath = Join-Path $repo 'pc/brain.py'
$statePath = Join-Path $ToolsRoot 'brain'
$configPath = Join-Path $statePath 'config.json'
$cloudflared = Join-Path $ToolsRoot 'cloudflared.exe'
$publisher = Join-Path $repo 'pc/tunnel_publish.py'
$stoppedFlag = Join-Path $statePath 'stopped.flag'
$taskName = 'AndroidAgentPcBrainWatchdog'
$reportScript = Join-Path $repo 'pc/weekly_report.py'
$telemetryScript = Join-Path $repo 'pc/telemetry.py'
$workspacePath = Join-Path $statePath 'empty-workspace'
$reportTasks = @{ 'AndroidAgentDailyCheck' = '--daily'; 'AndroidAgentWeeklyReport' = '--weekly' }
New-Item -ItemType Directory -Force -Path $statePath | Out-Null

function OwnedProcess([string]$Name) {
    $pidPath = Join-Path $statePath "$Name.pid"
    if (!(Test-Path -LiteralPath $pidPath)) { return $null }
    $processId = 0
    if (![int]::TryParse((Get-Content -LiteralPath $pidPath -Raw).Trim(), [ref]$processId)) { throw 'Invalid process record.' }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if (!$process) { return $null }
    $expected = if ($Name -eq 'server') { $scriptPath } else { $cloudflared }
    if (!$process.CommandLine -or $process.CommandLine.Replace('\', '/').IndexOf($expected.Replace('\', '/'), [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "PID belongs to another process; left untouched: $processId"
    }
    return $process
}

function Emit([string]$Type, [string[]]$Extra = @()) {
    # Best effort event for the PC event log (events.jsonl); never fails the watchdog.
    try {
        if ((Test-Path -LiteralPath $Python) -and (Test-Path -LiteralPath $workspacePath)) {
            & $Python -X utf8 $telemetryScript --workspace $workspacePath emit $Type @Extra 2>&1 | Out-Null
        }
    } catch { }
}
function TunnelUrl {
    $log = Join-Path $statePath 'tunnel.stderr.log'
    if (!(Test-Path -LiteralPath $log)) { return $null }
    $found = [regex]::Matches((Get-Content -LiteralPath $log -Raw), 'https://[a-z0-9-]+\.trycloudflare\.com') | Where-Object { $_.Value -ne 'https://api.trycloudflare.com' }
    if (!$found) { return $null }
    return @($found)[-1].Value
}
function TunnelAlive([string]$Url) {
    # Any HTTP answer from the PC helper (401 without the pairing code) means the tunnel reaches it; no answer or a Cloudflare error means it is dead.
    try { Invoke-WebRequest -Uri ($Url + '/health') -UseBasicParsing -TimeoutSec 15 | Out-Null; return $true }
    catch {
        $response = $_.Exception.Response
        if ($response) { return @(404, 502, 530, 1033) -notcontains [int]$response.StatusCode }
        return $false
    }
}
function StartServer {
    if (!(OwnedProcess 'server')) {
        $process = Start-Process -FilePath $Python -ArgumentList @('-X', 'utf8', ('"' + $scriptPath + '"'), '--config', ('"' + $configPath + '"')) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $statePath 'server.stdout.log') -RedirectStandardError (Join-Path $statePath 'server.stderr.log') -PassThru
        $process.Id | Set-Content -LiteralPath (Join-Path $statePath 'server.pid')
    }
}
function StartTunnel {
    if (!(OwnedProcess 'tunnel')) {
        $process = Start-Process -FilePath $cloudflared -ArgumentList @('tunnel', '--url', 'http://127.0.0.1:8766', '--no-autoupdate') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $statePath 'tunnel.stdout.log') -RedirectStandardError (Join-Path $statePath 'tunnel.stderr.log') -PassThru
        $process.Id | Set-Content -LiteralPath (Join-Path $statePath 'tunnel.pid')
        Emit 'tunnel_start'
    }
}
function PublishUrl {
    # Best effort: sends/pins "AA-URL <tunnel>" in the owner's Telegram chat when the address changed. Never prints credentials.
    for ($i = 0; $i -lt 30 -and !(TunnelUrl); $i++) { Start-Sleep -Seconds 1 }
    $result = & $Python -X utf8 $publisher --state-dir $statePath 2>&1
    $line = "$(Get-Date -Format s) publish: $($result -join ' ')"
    Add-Content -LiteralPath (Join-Path $statePath 'watchdog.log') -Value $line
    Write-Output ('URL publish: ' + ($result -join ' '))
}

if ($Action -eq 'install-autostart') {
    $vbs = Join-Path $statePath 'watchdog.vbs'
    $launch = 'CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ""' + (Join-Path $PSScriptRoot 'pc-brain.ps1') + '"" watchdog", 0, False'
    Set-Content -LiteralPath $vbs -Value $launch -Encoding Unicode
    $trigger = @(
        (New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"),
        (New-ScheduledTaskTrigger -Once -At (Get-Date).Date -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650))
    )
    $task = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('//B //Nologo "' + $vbs + '"')
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 10) -Hidden
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $taskName -Action $task -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Remove-Item -LiteralPath $stoppedFlag -ErrorAction SilentlyContinue
    Write-Output "Autostart installed: task $taskName (at logon and every 5 minutes; keeps helper and tunnel up, pins the new tunnel address in Telegram)."
    $Action = 'status'
}
elseif ($Action -eq 'uninstall-autostart') {
    if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }
    Remove-Item -LiteralPath (Join-Path $statePath 'watchdog.vbs') -ErrorAction SilentlyContinue
    Write-Output 'Autostart removed. Helper and tunnel, if running, stay up until stop.'
    exit
}

if ($Action -eq 'install-reports') {
    if (!(Test-Path -LiteralPath $Python)) { throw 'Python missing; pass -Python with your Python 3.11+ executable.' }
    New-Item -ItemType Directory -Force -Path (Join-Path $workspacePath 'reports') | Out-Null
    $triggers = @{
        'AndroidAgentDailyCheck'   = (New-ScheduledTaskTrigger -Daily -At '09:00')
        'AndroidAgentWeeklyReport' = (New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday -At '10:00')
    }
    foreach ($name in $reportTasks.Keys) {
        $vbs = Join-Path $statePath ("$name.vbs")
        $command = '"' + $Python + '" -X utf8 "' + $reportScript + '" --workspace "' + $workspacePath + '" ' + $reportTasks[$name]
        $launch = 'CreateObject("WScript.Shell").Run "' + $command.Replace('"', '""') + '", 0, False'
        Set-Content -LiteralPath $vbs -Value $launch -Encoding Unicode
        $reportAction = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument ('//B //Nologo "' + $vbs + '"')
        $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 15) -Hidden
        $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName $name -Action $reportAction -Trigger $triggers[$name] -Settings $settings -Principal $principal -Force | Out-Null
    }
    Write-Output 'Report tasks installed: AndroidAgentDailyCheck (daily 09:00, alert only past a threshold) and AndroidAgentWeeklyReport (Monday 10:00). Reports: empty-workspace/reports.'
    $Action = 'status'
}
elseif ($Action -eq 'uninstall-reports') {
    foreach ($name in $reportTasks.Keys) {
        if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $name -Confirm:$false }
        Remove-Item -LiteralPath (Join-Path $statePath "$name.vbs") -ErrorAction SilentlyContinue
    }
    Write-Output 'Report tasks removed. events.jsonl and reports/ are kept.'
    exit
}

if ($Action -eq 'watchdog') {
    # Does nothing after an explicit "stop" (until the next "start").
    if (Test-Path -LiteralPath $stoppedFlag) { exit }
    if (!(Test-Path -LiteralPath $Python) -or !(Test-Path -LiteralPath $cloudflared) -or !(Test-Path -LiteralPath $configPath)) { exit }
    StartServer
    StartTunnel
    $url = TunnelUrl
    # A short internet blip must not replace a healthy tunnel (its URL would change): restart only after 3 failed runs in a row.
    $failFile = Join-Path $statePath 'watchdog-fails.txt'
    if ($url -and !(TunnelAlive $url) -and !(TunnelAlive $url)) {
        $fails = 1 + [int](Get-Content -LiteralPath $failFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        Set-Content -LiteralPath $failFile -Value $fails
        if ($fails -ge 3) {
            $dead = OwnedProcess 'tunnel'
            if ($dead) { Stop-Process -Id $dead.ProcessId }
            Start-Sleep -Seconds 2
            StartTunnel
            Emit 'tunnel_restart' @('--flag', 'fails=3')
            Remove-Item -LiteralPath $failFile -ErrorAction SilentlyContinue
        }
    } else { Remove-Item -LiteralPath $failFile -ErrorAction SilentlyContinue }
    PublishUrl | Out-Null
    exit
}

if ($Action -eq 'stop') {
    New-Item -ItemType File -Force -Path $stoppedFlag | Out-Null
    foreach ($name in @('tunnel', 'server')) {
        $process = OwnedProcess $name
        if ($process) { Stop-Process -Id $process.ProcessId }
    }
    Write-Output 'Own PC helper and tunnel stopped. Phone commands /meal still work without AI.'
    exit
}

if ($Action -eq 'start') {
    if (!(Test-Path -LiteralPath $Python)) { throw 'Python missing; pass -Python with your Python 3.11+ executable.' }
    if (!(Test-Path -LiteralPath $cloudflared)) { throw 'Install official cloudflared-windows-amd64.exe as ToolsRoot/cloudflared.exe. See docs/PC_BRAIN_RU.md.' }
    if (!(Test-Path -LiteralPath $configPath)) {
        & $Python -X utf8 $scriptPath --config $configPath --init
        if ($LASTEXITCODE -ne 0) { throw 'Pairing initialization failed.' }
    }
    Remove-Item -LiteralPath $stoppedFlag -ErrorAction SilentlyContinue
    StartServer
    StartTunnel
    PublishUrl
}

$serverProcess = OwnedProcess 'server'
$tunnelProcess = OwnedProcess 'tunnel'
Write-Output ('PC helper running: ' + [bool]$serverProcess + '; HTTPS tunnel running: ' + [bool]$tunnelProcess)
if ($serverProcess -and (Test-Path -LiteralPath $configPath)) {
    $health = $null
    $attempts = if ($Action -eq 'start') { 8 } else { 1 }
    for ($attempt = 0; $attempt -lt $attempts; $attempt++) {
        try {
            $credentials = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8766/health' -Headers @{ Authorization = 'Bearer ' + $credentials.pairing_token } -TimeoutSec 2
            break
        } catch {
            if ($attempt + 1 -lt $attempts) { Start-Sleep -Milliseconds 300 }
        }
    }
    Write-Output ('HTTP ready: ' + [bool]($health -and $health.status -eq 'ready'))
    if ($health -and $health.subscription_limited) {
        $reset = if ($health.reset -match '^([01][0-9]|2[0-3]):[0-5][0-9]$') { $health.reset } else { 'unknown' }
        Write-Output ('Subscription limit, reset at PC local time: ' + $reset)
    }
}
if ($Action -eq 'pair') {
    if (!$serverProcess -or !$tunnelProcess) { throw 'Start the helper first.' }
    $log = Get-Content -LiteralPath (Join-Path $statePath 'tunnel.stderr.log') -Raw
    $match = [regex]::Match($log, 'https://[a-z0-9-]+\.trycloudflare\.com')
    if (!$match.Success) { throw 'HTTPS address not ready; retry shortly.' }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $endpoint = [System.Net.WebUtility]::HtmlEncode($match.Value)
    $pairing = [System.Net.WebUtility]::HtmlEncode($config.pairing_token)
    $page = @"
<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Android Agent - подключение ПК</title>
<style>body{font:18px system-ui;background:#f3f6fa;color:#163049;max-width:680px;margin:8vh auto;padding:24px}main{background:white;border-radius:24px;padding:32px}input{box-sizing:border-box;width:100%;padding:14px;border:1px solid #b7c9d4;border-radius:12px;font:15px monospace}h1{font-size:28px}p{line-height:1.5}small{color:#526878}label{display:block;margin:24px 0 8px}</style>
<main><h1>ИИ по твоей подписке</h1><p>В Android Agent открой вкладку ИИ, скопируй эти поля, включи согласие и сохрани. API-ключ не нужен.</p><label>HTTPS-адрес ПК</label><input readonly value="$endpoint" onclick="this.select()"><label>Код сопряжения</label><input readonly value="$pairing" onclick="this.select()"><p><small>Код даёт доступ к помощнику. Не пересылай страницу и не публикуй её. ПК и помощник должны работать. После перезапуска временного tunnel адрес меняется.</small></p></main></html>
"@
    $pagePath = Join-Path $statePath 'pairing.html'
    [IO.File]::WriteAllText($pagePath, $page, [Text.UTF8Encoding]::new($false))
    if (!$NoOpen) { Start-Process -FilePath $pagePath }
    else { Write-Output ('Private pairing page prepared: ' + $pagePath) }
}

if ($Action -eq 'status') {
    $autostart = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Write-Output ('Autostart task: ' + $(if ($autostart) { [string]$autostart.State } else { 'not installed' }))
    foreach ($name in ($reportTasks.Keys | Sort-Object)) {
        $task = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
        $next = if ($task) { (Get-ScheduledTaskInfo -TaskName $name).NextRunTime } else { $null }
        Write-Output ("Report task ${name}: " + $(if ($task) { [string]$task.State + ', next run ' + $next } else { 'not installed' }))
    }
    $published = Join-Path $statePath 'published-url.txt'
    if (Test-Path -LiteralPath $published) { Write-Output ('Address pinned in Telegram: ' + (Get-Content -LiteralPath $published -Raw).Trim()) }
}
