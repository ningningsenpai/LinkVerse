[CmdletBinding()]
param(
    [string]$K6Executable = '',
    [ValidateRange(1, 500)][int]$NormalVus = 50,
    [ValidateRange(100, 100000)][int]$NormalIterations = 1000,
    [ValidateRange(1, 500)][int]$PaymentVus = 25,
    [ValidateRange(100, 100000)][int]$PaymentIterations = 500,
    [ValidateRange(100, 1000)][int]$SeckillUsers = 1000,
    [ValidateRange(1, 1000)][int]$SeckillVus = 100,
    [switch]$SkipTokenRefresh
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
foreach ($entry in @{ gateway = 18080; identity = 18081; trade = 18082; payment = 18083 }.GetEnumerator()) {
    Wait-LinkVerseHealth -Service $entry.Key -Port $entry.Value -TimeoutSeconds 15
}

if ([string]::IsNullOrWhiteSpace($K6Executable)) {
    $bundled = Join-Path $context.RuntimeRoot 'tools/k6-v2.2.0-windows-amd64/k6.exe'
    $K6Executable = if (Test-Path -LiteralPath $bundled) { $bundled } else { 'k6' }
}
Assert-LinkVerseCommand -Name $K6Executable
$versionText = (& $K6Executable version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '\bv2\.2\.0\b') {
    throw "k6 版本不符合锁定值 2.2.0：$versionText"
}

if (-not $SkipTokenRefresh) {
    & (Join-Path $PSScriptRoot 'seed.ps1') -UserCount $SeckillUsers
}
$seedStatePath = Get-LinkVerseSeedStatePath
$tokenPath = Join-Path $context.RuntimeRoot 'k6-tokens.json'
if (-not (Test-Path -LiteralPath $seedStatePath) -or -not (Test-Path -LiteralPath $tokenPath)) {
    throw '缺少压测用户或令牌，请移除 -SkipTokenRefresh 后重试。'
}
& (Join-Path $PSScriptRoot 'prepare-load-test.ps1')
$seed = Get-Content -LiteralPath $seedStatePath -Raw | ConvertFrom-Json
$loadStatePath = Join-Path $context.RuntimeRoot 'load-test-state.json'
$loadState = Get-Content -LiteralPath $loadStatePath -Raw | ConvertFrom-Json
$runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$rawRoot = Join-Path $context.TestResultRoot "raw/load-$runId"
$generatedRoot = Join-Path $context.TestResultRoot "generated/load-$runId"
New-Item -ItemType Directory -Path $rawRoot -Force | Out-Null
New-Item -ItemType Directory -Path $generatedRoot -Force | Out-Null
$k6Root = Join-Path $context.PlatformRoot 'tests/k6'
$env:BASE_URL = 'http://127.0.0.1:18080'
$env:ACCESS_TOKEN = [string]@($seed.users)[0].access_token

function Invoke-LoadSuite {
    param([string]$Name, [hashtable]$Environment)
    foreach ($entry in $Environment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    $env:SUMMARY_JSON = Join-Path $generatedRoot "$Name-summary.json"
    $env:SUMMARY_MD = Join-Path $generatedRoot "$Name-summary.md"
    $k6Output = & $K6Executable run '--out' "json=$(Join-Path $rawRoot "$Name.json")" `
        (Join-Path $k6Root "$Name.js") 2>&1
    $k6ExitCode = $LASTEXITCODE
    $k6Output | Out-Host
    if ($k6ExitCode -ne 0) { throw "k6 场景失败：$Name" }
    $summary = Get-Content -LiteralPath $env:SUMMARY_JSON -Raw | ConvertFrom-Json
    if ([double]$summary.checks_rate -ne 1) { throw "k6 场景存在断言失败：$Name" }
    return $summary
}

$normal = Invoke-LoadSuite -Name 'normal-trade' -Environment @{
    LISTING_ID = $loadState.normal_listing_id; VUS = $NormalVus; ITERATIONS = $NormalIterations
}
$payment = Invoke-LoadSuite -Name 'payment-chain' -Environment @{
    PAYMENT_LISTING_ID = $loadState.payment_listing_id; VUS = $PaymentVus; ITERATIONS = $PaymentIterations
}
$seckill = Invoke-LoadSuite -Name 'seckill' -Environment @{
    CAMPAIGN_ID = $loadState.campaign_id; TOKENS_FILE = $tokenPath
    USERS = $SeckillUsers; SECKILL_VUS = [Math]::Min($SeckillVus, $SeckillUsers)
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
do {
    $sql = @"
SELECT COUNT(*),
       COALESCE(SUM(status IN ('PUBLISH_PENDING', 'PUBLISHED')), 0),
       COALESCE(SUM(status IN ('ORDER_CREATED', 'COMMITTED')), 0),
       (SELECT COUNT(*) FROM trade_order o JOIN seckill_reservation r ON r.id = o.reservation_id WHERE r.campaign_id = $($loadState.campaign_id)),
       (SELECT available FROM sku_stock WHERE listing_id = $($loadState.seckill_listing_id))
FROM seckill_reservation WHERE campaign_id = $($loadState.campaign_id);
"@
    $databaseLine = @((Invoke-LinkVerseTradeSql -Context $context -Sql $sql))[-1]
    $parts = ([string]$databaseLine).Trim() -split "`t"
    if ($parts.Count -eq 5 -and [int]$parts[1] -eq 0 -and [int]$parts[0] -ge [int]$seckill.seckill_accepted) {
        break
    }
    Start-Sleep -Seconds 1
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($parts.Count -ne 5) { throw '无法解析秒杀数据库核验结果。' }
$redisBase = "lv:mvp:trade:{seckill:$($loadState.campaign_id)}:"
$redisDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
do {
    $redisStock = [int](@(Invoke-LinkVerseRedisCli -Context $context `
        -RedisArguments @('HGET', "${redisBase}campaign", 'stock'))[-1])
    $redisPending = [int](@(Invoke-LinkVerseRedisCli -Context $context `
        -RedisArguments @('HLEN', "${redisBase}pending"))[-1])
    $redisPendingOrder = [int](@(Invoke-LinkVerseRedisCli -Context $context `
        -RedisArguments @('ZCARD', "${redisBase}pending-order"))[-1])
    if ($redisStock -eq [int]$parts[4] -and $redisPending -eq 0 -and $redisPendingOrder -eq 0) {
        break
    }
    Start-Sleep -Seconds 1
} while ([DateTimeOffset]::UtcNow -lt $redisDeadline)
$verification = [PSCustomObject]@{
    run_id = $runId
    generated_at = [DateTimeOffset]::UtcNow.ToString('O')
    data = $loadState
    normal = $normal
    payment = $payment
    seckill = $seckill
    database = [PSCustomObject]@{
        reservation_count = [int]$parts[0]
        intermediate_count = [int]$parts[1]
        completed_reservation_count = [int]$parts[2]
        order_count = [int]$parts[3]
        mysql_stock = [int]$parts[4]
    }
    redis = [PSCustomObject]@{
        stock = $redisStock
        pending_count = $redisPending
        pending_order_count = $redisPendingOrder
    }
}
if ($verification.seckill.seckill_accepted -gt $loadState.seckill_stock -or
    $verification.database.order_count -gt $loadState.seckill_stock -or
    $verification.database.mysql_stock -lt 0 -or
    $verification.database.intermediate_count -ne 0 -or
    $verification.redis.stock -ne $verification.database.mysql_stock -or
    $verification.redis.pending_count -ne 0 -or
    $verification.redis.pending_order_count -ne 0) {
    throw '秒杀压测未通过无超卖或最终收敛核验。'
}
$verificationPath = Join-Path $generatedRoot 'load-test-verification.json'
$verification | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $verificationPath -Encoding utf8
$verification | ConvertTo-Json -Depth 10
Write-Host "完整压测通过；核验结果：$verificationPath"
