param([ValidateRange(120, 1000)][int]$ItemCount = 120)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\_delivery.ps1')
$context = Initialize-LinkVerseDeliveryEnvironment
& (Join-Path $PSScriptRoot '..\seed.ps1') -UserCount 20
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$firstId = 10000000000000 + $stamp
$prefix = "REC_$stamp"
$statements = [System.Collections.Generic.List[string]]::new()
$statements.Add('START TRANSACTION;')
for ($category = 0; $category -lt 6; $category++) {
    $statements.Add("INSERT INTO book_category (code,name,status) VALUES ('${prefix}_$category','推荐迭代夹具类目 $category','ACTIVE');")
}
for ($index = 0; $index -lt $ItemCount; $index++) {
    $itemId = $firstId + $index
    $category = $index % 6
    $seller = 8000000 + ($index % 20)
    $price = 20 + ($index % 60)
    $statements.Add("INSERT INTO book_listing (id,seller_id,category_id,title,author,description,unit_price,currency,status,published_at,version) SELECT $itemId,$seller,id,'推荐迭代夹具 $index','夹具作者 $category','本地 SCRIPTED 训练与 HTTP 验证商品',$price,'CNY','ON_SALE',UTC_TIMESTAMP(6) - INTERVAL 1 DAY,0 FROM book_category WHERE code='${prefix}_$category';")
    $statements.Add("INSERT INTO sku_stock (listing_id,available,version) VALUES ($itemId,1000,0);")
}
$statements.Add('COMMIT;')
$databaseCommand = 'MYSQL_PWD="$TRADE_APP_PASSWORD" mysql --protocol=socket --user="$TRADE_APP_USER" --database="$TRADE_DB_NAME" --default-character-set=utf8mb4 --batch'
$dockerArguments = @('compose', '--env-file', $context.Infrastructure.EnvironmentFile,
    '--env-file', $context.Infrastructure.VersionsFile, '--file', $context.Infrastructure.ComposeFile,
    'exec', '--no-TTY', 'mysql', 'sh', '-ec', $databaseCommand)
# 从标准输入传输整段事务，避免 Windows 对长命令行的长度限制。
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
($statements -join "`n") | & docker @dockerArguments
if ($LASTEXITCODE -ne 0) { throw '推荐夹具事务执行失败' }
$state = @{
    fixture_id = $prefix
    traffic_origin = 'SCRIPTED'
    first_listing_id = $firstId
    last_listing_id = $firstId + $ItemCount - 1
    item_count = $ItemCount
    user_count = 20
    created_at = [DateTimeOffset]::UtcNow.ToString('O')
    seed_state_path = Get-LinkVerseSeedStatePath
}
$state | ConvertTo-Json | Set-Content (Join-Path $context.RuntimeRoot 'recommendation-fixture.json') -Encoding utf8
Write-Host "推荐夹具已创建：$prefix，$ItemCount 件商品、6 类目、20 卖家；业务数据按新增编号隔离。"
