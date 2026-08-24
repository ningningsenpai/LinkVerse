[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$username = "linkverse_fault_$suffix"
$password = "Lv!$([Guid]::NewGuid().ToString('N'))Aa1"
$register = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/auth/register' `
    -Body @{ username = $username; password = $password }
Assert-LinkVerseStatus -Response $register -Expected 201 -Operation '创建故障测试隔离用户'
$login = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/auth/login' `
    -Body @{ username = $username; password = $password }
Assert-LinkVerseStatus -Response $login -Expected 200 -Operation '故障测试用户登录'
$token = [string]$login.Json.access_token
$results = [System.Collections.Generic.List[object]]::new()

function Invoke-ComposeService {
    param([string[]]$Arguments)
    Invoke-LinkVerseCompose -Context $context.Infrastructure `
        -EnvironmentFile $context.Infrastructure.EnvironmentFile -ComposeArguments $Arguments
}

function New-OrdinaryOrder {
    param([string]$Scenario)
    $response = Invoke-LinkVerseJsonRequest -Method Post `
        -Uri 'http://127.0.0.1:18080/api/v1/orders' `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "$Scenario-$([Guid]::NewGuid().ToString('N'))" } `
        -Body @{ listing_id = 10001; quantity = 1 }
    Assert-LinkVerseStatus -Response $response -Expected 201 -Operation "$Scenario 下普通下单"
    return $response
}

try {
    Invoke-ComposeService @('stop', 'redis')
    $redisSeckill = Invoke-LinkVerseJsonRequest -Method Post `
        -Uri 'http://127.0.0.1:18080/api/v1/seckill/campaigns/20001/reservations' `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "fault-redis-$([Guid]::NewGuid().ToString('N'))" }
    Assert-LinkVerseStatus -Response $redisSeckill -Expected 503 -Operation 'Redis 故障时关闭秒杀准入'
    $redisOrdinary = New-OrdinaryOrder 'fault-redis'
    $results.Add([PSCustomObject]@{
        scenario = 'redis_unavailable'; seckill_status = 503;
        ordinary_order_status = $redisOrdinary.StatusCode; conclusion = '通过'
    })
    Invoke-ComposeService @('up', '--detach', '--wait', '--wait-timeout', '60', 'redis')

    $paymentOrder = New-OrdinaryOrder 'fault-rabbit-payment'
    $orderNo = [string]$paymentOrder.Json.order_no
    $intent = Invoke-LinkVerseJsonRequest -Method Put `
        -Uri "http://127.0.0.1:18080/api/v1/orders/$orderNo/payment-intent" `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "fault-pay-$orderNo" }
    Assert-LinkVerseStatus -Response $intent -Expected 200 -Operation '故障测试创建支付 Intent'

    Invoke-ComposeService @('stop', 'rabbitmq')
    $rabbitSeckill = Invoke-LinkVerseJsonRequest -Method Post `
        -Uri 'http://127.0.0.1:18080/api/v1/seckill/campaigns/20001/reservations' `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "fault-rabbit-$([Guid]::NewGuid().ToString('N'))" }
    Assert-LinkVerseStatus -Response $rabbitSeckill -Expected 503 -Operation 'RabbitMQ 故障时关闭秒杀准入'
    $rabbitOrdinary = New-OrdinaryOrder 'fault-rabbit'
    $confirmed = Invoke-LinkVerseJsonRequest -Method Post `
        -Uri "http://127.0.0.1:18080/api/v1/mock-provider/payment-intents/$($intent.Json.intent_no)/confirm" `
        -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = "fault-confirm-$orderNo" }
    Assert-LinkVerseStatus -Response $confirmed -Expected 200 -Operation 'MQ 中断时提交支付事实'
    Invoke-ComposeService @('up', '--detach', '--wait', '--wait-timeout', '60', 'rabbitmq')

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
    do {
        $order = Invoke-LinkVerseJsonRequest -Method Get `
            -Uri "http://127.0.0.1:18080/api/v1/orders/$orderNo" `
            -Headers @{ Authorization = "Bearer $token" }
        if ($order.Json.status -eq 'PAID') { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($order.Json.status -ne 'PAID') {
        throw 'RabbitMQ 恢复后 Outbox 未在限定时间内将订单收敛为 PAID。'
    }
    $results.Add([PSCustomObject]@{
        scenario = 'rabbitmq_unavailable_and_recovery'; seckill_status = 503;
        ordinary_order_status = $rabbitOrdinary.StatusCode; payment_status = $confirmed.Json.status;
        recovered_order_status = $order.Json.status; conclusion = '通过'
    })
}
finally {
    Invoke-ComposeService @('up', '--detach', '--wait', '--wait-timeout', '60', 'redis', 'rabbitmq')
}

$rawRoot = Join-Path $context.TestResultRoot 'raw'
New-Item -ItemType Directory -Path $rawRoot -Force | Out-Null
$path = Join-Path $rawRoot 'fault-test.json'
$results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8
Write-Host "故障恢复测试通过；原始结果已保存：$path"
