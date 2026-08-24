[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
$trade = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18082/internal/v1/reconciliation'
$payment = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18083/internal/v1/reconciliation'
Assert-LinkVerseStatus -Response $trade -Expected 200 -Operation 'Trade 对账'
Assert-LinkVerseStatus -Response $payment -Expected 200 -Operation 'Payment 对账'

$result = [PSCustomObject]@{
    generated_at = [DateTimeOffset]::UtcNow.ToString('O')
    trade = $trade.Json
    payment = $payment.Json
}
$resultRoot = Join-Path $context.TestResultRoot 'generated'
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$path = Join-Path $resultRoot 'reconciliation.json'
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8
$result | ConvertTo-Json -Depth 8
Write-Host "对账结果已保存：$path"
