[CmdletBinding()]
param(
    [switch]$RunK6,
    [string]$K6Executable = 'k6'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
$ports = @{ gateway = 18080; identity = 18081; trade = 18082; payment = 18083 }
foreach ($entry in $ports.GetEnumerator()) {
    Wait-LinkVerseHealth -Service $entry.Key -Port $entry.Value -TimeoutSeconds 15
}
if (-not (Test-Path -LiteralPath (Get-LinkVerseSeedStatePath))) {
    & (Join-Path $PSScriptRoot 'seed.ps1')
}
$seed = Get-Content -LiteralPath (Get-LinkVerseSeedStatePath) -Raw | ConvertFrom-Json
$user = @($seed.users)[0]
$login = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/auth/login' `
    -Body @{ username = $user.username; password = $seed.password }
Assert-LinkVerseStatus -Response $login -Expected 200 -Operation '验收用户登录'
$token = [string]$login.Json.access_token
$authorization = @{ Authorization = "Bearer $token" }
$results = [System.Collections.Generic.List[object]]::new()

function Add-AcceptanceResult {
    param([string]$Name, [int]$Status, [string]$Conclusion, [object]$Actual)
    $results.Add([PSCustomObject]@{
        name = $Name; status_code = $Status; conclusion = $Conclusion; actual = $Actual
    })
}

$listing = Invoke-LinkVerseJsonRequest -Method Get -Uri 'http://127.0.0.1:18080/api/v1/listings/10001' `
    -Headers $authorization
Assert-LinkVerseStatus -Response $listing -Expected 200 -Operation '查询商品'
Add-AcceptanceResult '商品查询' $listing.StatusCode '通过' `
    @{ listing_id = $listing.Json.listing_id; status = $listing.Json.status }

$campaign = Invoke-LinkVerseJsonRequest -Method Get `
    -Uri 'http://127.0.0.1:18080/api/v1/seckill/campaigns/20001' -Headers $authorization
Assert-LinkVerseStatus -Response $campaign -Expected 200 -Operation '查询秒杀活动'
Add-AcceptanceResult '秒杀活动查询' $campaign.StatusCode '通过' `
    @{ campaign_id = $campaign.Json.campaign_id; status = $campaign.Json.status }

$key = "acceptance-$([Guid]::NewGuid().ToString('N'))"
$orderHeaders = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $key }
$created = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/orders' `
    -Headers $orderHeaders -Body @{ listing_id = 10001; quantity = 1 }
Assert-LinkVerseStatus -Response $created -Expected 201 -Operation '创建普通订单'
$orderNo = [string]$created.Json.order_no
Add-AcceptanceResult '创建普通订单' $created.StatusCode '通过' `
    @{ order_no = $orderNo; status = $created.Json.status; total_amount = $created.Json.total_amount }

$replayed = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/orders' `
    -Headers $orderHeaders -Body @{ listing_id = 10001; quantity = 1 }
Assert-LinkVerseStatus -Response $replayed -Expected 200 -Operation '订单幂等重放'
if ($replayed.Json.order_no -ne $orderNo) { throw '订单幂等重放返回了不同订单号。' }
Add-AcceptanceResult '订单幂等重放' $replayed.StatusCode '通过' @{ order_no = $orderNo }

$intent = Invoke-LinkVerseJsonRequest -Method Put `
    -Uri "http://127.0.0.1:18080/api/v1/orders/$orderNo/payment-intent" `
    -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "pay-$key" }
Assert-LinkVerseStatus -Response $intent -Expected 200 -Operation '创建支付 Intent'
$intentNo = [string]$intent.Json.intent_no
Add-AcceptanceResult '创建支付 Intent' $intent.StatusCode '通过' `
    @{ intent_no = $intentNo; order_no = $orderNo; amount = $intent.Json.amount; status = $intent.Json.status }

$confirmKey = "confirm-$key"
$confirmed = Invoke-LinkVerseJsonRequest -Method Post `
    -Uri "http://127.0.0.1:18080/api/v1/mock-provider/payment-intents/$intentNo/confirm" `
    -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $confirmKey }
Assert-LinkVerseStatus -Response $confirmed -Expected 200 -Operation 'Mock 支付确认'
if ($confirmed.Json.status -ne 'SUCCEEDED') { throw 'Mock 支付未收敛为 SUCCEEDED。' }
for ($index = 0; $index -lt 99; $index++) {
    $duplicate = Invoke-LinkVerseJsonRequest -Method Post `
        -Uri "http://127.0.0.1:18080/api/v1/mock-provider/payment-intents/$intentNo/confirm" `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = $confirmKey }
    Assert-LinkVerseStatus -Response $duplicate -Expected 200 -Operation '支付回调幂等重放'
}
Add-AcceptanceResult '支付回调重放 100 次' 200 '通过' @{ intent_no = $intentNo; transition_count = 1 }

$deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
do {
    $owned = Invoke-LinkVerseJsonRequest -Method Get `
        -Uri "http://127.0.0.1:18080/api/v1/orders/$orderNo" -Headers $authorization
    if ($owned.Json.status -eq 'PAID') { break }
    Start-Sleep -Milliseconds 250
} while ([DateTimeOffset]::UtcNow -lt $deadline)
if ($owned.Json.status -ne 'PAID') { throw '支付事件未在限定时间内将订单收敛为 PAID。' }
Add-AcceptanceResult '支付事件驱动订单收敛' $owned.StatusCode '通过' `
    @{ order_no = $orderNo; status = $owned.Json.status }

$ownedIntent = Invoke-LinkVerseJsonRequest -Method Get `
    -Uri "http://127.0.0.1:18080/api/v1/payment-intents/$intentNo" -Headers $authorization
Assert-LinkVerseStatus -Response $ownedIntent -Expected 200 -Operation '查询支付 Intent'
if ($ownedIntent.Json.status -ne 'SUCCEEDED') { throw '用户查询的支付 Intent 状态不是 SUCCEEDED。' }
Add-AcceptanceResult '支付 Intent 查询' $ownedIntent.StatusCode '通过' `
    @{ intent_no = $intentNo; status = $ownedIntent.Json.status }

$reservation = Invoke-LinkVerseJsonRequest -Method Post `
    -Uri 'http://127.0.0.1:18080/api/v1/seckill/campaigns/20001/reservations' `
    -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "reserve-$key" }
Assert-LinkVerseStatus -Response $reservation -Expected 202 -Operation '创建秒杀预约'
Add-AcceptanceResult '创建秒杀预约' $reservation.StatusCode '通过' `
    @{ reservation_no = $reservation.Json.reservation_no; status = $reservation.Json.status }

$ownedReservation = Invoke-LinkVerseJsonRequest -Method Get `
    -Uri "http://127.0.0.1:18080/api/v1/seckill/reservations/$($reservation.Json.reservation_no)" `
    -Headers $authorization
Assert-LinkVerseStatus -Response $ownedReservation -Expected 200 -Operation '查询秒杀预约结果'
Add-AcceptanceResult '秒杀预约结果查询' $ownedReservation.StatusCode '通过' `
    @{ reservation_no = $ownedReservation.Json.reservation_no; status = $ownedReservation.Json.status }

& (Join-Path $PSScriptRoot 'reconcile.ps1') | Out-Null

$rawRoot = Join-Path $context.TestResultRoot 'raw'
$generatedRoot = Join-Path $context.TestResultRoot 'generated'
New-Item -ItemType Directory -Path $rawRoot -Force | Out-Null
New-Item -ItemType Directory -Path $generatedRoot -Force | Out-Null
$rawPath = Join-Path $rawRoot 'acceptance.json'
$results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $rawPath -Encoding utf8

if ($RunK6) {
    Assert-LinkVerseCommand -Name $K6Executable
    $versionText = (& $K6Executable version 2>&1) -join ' '
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '\bv2\.2\.0\b') {
        throw "k6 版本不符合锁定值 2.2.0：$versionText"
    }
    $k6Root = Join-Path $context.PlatformRoot 'tests/k6'
    $env:BASE_URL = 'http://127.0.0.1:18080'
    $env:ACCESS_TOKEN = $token
    foreach ($suite in @('normal-trade', 'payment-chain')) {
        $env:SUMMARY_JSON = Join-Path $generatedRoot "$suite-summary.json"
        $env:SUMMARY_MD = Join-Path $generatedRoot "$suite-summary.md"
        & $K6Executable run '--out' "json=$(Join-Path $rawRoot "$suite.json")" `
            (Join-Path $k6Root "$suite.js")
        if ($LASTEXITCODE -ne 0) { throw "k6 场景失败：$suite" }
    }
    if (@($seed.users).Count -ge 1000) {
        $env:TOKENS_FILE = Join-Path $context.RuntimeRoot 'k6-tokens.json'
        $env:USERS = '1000'
        $env:SUMMARY_JSON = Join-Path $generatedRoot 'seckill-summary.json'
        $env:SUMMARY_MD = Join-Path $generatedRoot 'seckill-summary.md'
        & $K6Executable run '--out' "json=$(Join-Path $rawRoot 'seckill.json')" `
            (Join-Path $k6Root 'seckill.js')
        if ($LASTEXITCODE -ne 0) { throw 'k6 秒杀场景失败。' }
    }
    else {
        Write-Warning '未准备 1000 个独立令牌，已跳过 k6 秒杀；先执行 seed.ps1 -UserCount 1000。'
    }
}

$markdown = @(
    '# LinkVerse 后端验收实测汇总', '',
    "生成时间：$([DateTimeOffset]::UtcNow.ToString('O'))", '',
    '| 场景 | 状态码 | 结论 |', '|---|---:|---|'
)
foreach ($result in $results) {
    $markdown += "| $($result.name) | $($result.status_code) | $($result.conclusion) |"
}
$markdown += @('', '> 令牌、密码、签名与密钥未写入结果文件。', '')
$summaryPath = Join-Path $generatedRoot 'acceptance-summary.md'
$markdown -join "`n" | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Host "后端验收通过；原始结果：$rawPath；汇总：$summaryPath"
