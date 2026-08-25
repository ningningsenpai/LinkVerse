[CmdletBinding()]
param(
    [ValidateRange(100, 100000)][int]$NormalStock = 10000,
    [ValidateRange(100, 100000)][int]$PaymentStock = 10000,
    [ValidateRange(1, 10000)][int]$SeckillStock = 100
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
Assert-LinkVerseCommand -Name 'docker'
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$normalListingId = 9000000000000 + $suffix
$paymentListingId = $normalListingId + 1
$seckillListingId = $normalListingId + 2
$campaignId = $normalListingId + 3
$campaignNo = [Guid]::NewGuid().ToString('N')

$sql = @"
INSERT INTO book_listing (id, seller_id, title, author, description, unit_price, currency, status, version)
VALUES
  ($normalListingId, 9000001, 'LinkVerse Normal Load Test', 'LinkVerse', '独立普通交易压测数据', 39.9000, 'CNY', 'ON_SALE', 0),
  ($paymentListingId, 9000001, 'LinkVerse Payment Load Test', 'LinkVerse', '独立支付链路压测数据', 59.9000, 'CNY', 'ON_SALE', 0),
  ($seckillListingId, 9000001, 'LinkVerse Seckill Load Test', 'LinkVerse', '独立秒杀压测数据', 19.9000, 'CNY', 'ON_SALE', 0);
INSERT INTO sku_stock (listing_id, available, version)
VALUES
  ($normalListingId, $NormalStock, 0),
  ($paymentListingId, $PaymentStock, 0),
  ($seckillListingId, $SeckillStock, 0);
INSERT INTO seckill_campaign (id, campaign_no, listing_id, version, status, initial_stock, starts_at, ends_at)
VALUES ($campaignId, '$campaignNo', $seckillListingId, 1, 'ENABLED', $SeckillStock,
        UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE, UTC_TIMESTAMP(6) + INTERVAL 2 HOUR);
"@
Invoke-LinkVerseTradeSql -Context $context -Sql $sql | Out-Null

New-Item -ItemType Directory -Path $context.RuntimeRoot -Force | Out-Null
$state = [PSCustomObject]@{
    generated_at = [DateTimeOffset]::UtcNow.ToString('O')
    normal_listing_id = $normalListingId
    payment_listing_id = $paymentListingId
    seckill_listing_id = $seckillListingId
    campaign_id = $campaignId
    normal_stock = $NormalStock
    payment_stock = $PaymentStock
    seckill_stock = $SeckillStock
}
$statePath = Join-Path $context.RuntimeRoot 'load-test-state.json'
$state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding utf8
Write-Host "独立压测数据已准备：普通商品 $normalListingId；支付商品 $paymentListingId；秒杀活动 $campaignId。"
