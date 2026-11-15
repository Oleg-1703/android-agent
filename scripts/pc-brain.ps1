param(
    [ValidateSet('start', 'stop', 'status', 'pair')][string]$Action = 'status',
    [string]$ToolsRoot = "$env:USERPROFILE/.local/share/android-agent-tools",
    [string]$Python = "$env:USERPROFILE/.local/bin/python3.14.exe"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$scriptPath = Join-Path $repo 'pc/brain.py'
$statePath = Join-Path $ToolsRoot 'brain'
$configPath = Join-Path $statePath 'config.json'
$cloudflared = Join-Path $ToolsRoot 'cloudflared.exe'
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

if ($Action -eq 'stop') {
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
    if (!(OwnedProcess 'server')) {
        $process = Start-Process -FilePath $Python -ArgumentList @('-X', 'utf8', ('"' + $scriptPath + '"'), '--config', ('"' + $configPath + '"')) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $statePath 'server.stdout.log') -RedirectStandardError (Join-Path $statePath 'server.stderr.log') -PassThru
        $process.Id | Set-Content -LiteralPath (Join-Path $statePath 'server.pid')
    }
    if (!(OwnedProcess 'tunnel')) {
        $process = Start-Process -FilePath $cloudflared -ArgumentList @('tunnel', '--url', 'http://127.0.0.1:8766', '--no-autoupdate') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $statePath 'tunnel.stdout.log') -RedirectStandardError (Join-Path $statePath 'tunnel.stderr.log') -PassThru
        $process.Id | Set-Content -LiteralPath (Join-Path $statePath 'tunnel.pid')
    }
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
    Start-Process -FilePath $pagePath
}

