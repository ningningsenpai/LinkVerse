[CmdletBinding()]
param(
    [ValidateRange(1, 1000)][int]$UserCount = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Initialize-LinkVerseDeliveryEnvironment
Wait-LinkVerseHealth -Service 'linkverse-gateway' -Port 18080 -TimeoutSeconds 10
New-Item -ItemType Directory -Path $context.RuntimeRoot -Force | Out-Null
$statePath = Get-LinkVerseSeedStatePath
$state = $null
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
}
if ($null -eq $state) {
    $random = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $state = [PSCustomObject]@{
        password = [Convert]::ToBase64String($random).Replace('/', 'A').Replace('+', 'B')
        users = @()
    }
}

$users = [System.Collections.Generic.List[object]]::new()
foreach ($existing in @($state.users)) {
    $users.Add($existing)
}
for ($index = $users.Count; $index -lt $UserCount; $index++) {
    $username = "linkverse_mvp_$($index.ToString('D4'))"
    $register = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/auth/register' `
        -Body @{ username = $username; password = $state.password }
    if ($register.StatusCode -notin @(201, 409)) {
        throw "创建本地测试用户失败：$username，状态码 $($register.StatusCode)"
    }
    $login = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/auth/login' `
        -Body @{ username = $username; password = $state.password }
    Assert-LinkVerseStatus -Response $login -Expected 200 -Operation "登录本地测试用户 $username"
    $users.Add([PSCustomObject]@{
        username = $username
        user_id = if ($register.StatusCode -eq 201) { $register.Json.user_id } else { $null }
        access_token = $login.Json.access_token
    })
}

# 每次执行都刷新已有用户的短期令牌，避免 k6 使用过期令牌。
for ($index = 0; $index -lt [Math]::Min($UserCount, $users.Count); $index++) {
    $login = Invoke-LinkVerseJsonRequest -Method Post -Uri 'http://127.0.0.1:18080/api/v1/auth/login' `
        -Body @{ username = $users[$index].username; password = $state.password }
    Assert-LinkVerseStatus -Response $login -Expected 200 -Operation "刷新本地测试用户 $($users[$index].username)"
    $users[$index].access_token = $login.Json.access_token
}
$state.users = @($users)
$state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $statePath -Encoding utf8

$tokenPath = Join-Path $context.RuntimeRoot 'k6-tokens.json'
$tokens = @($users | Select-Object -First $UserCount | ForEach-Object { $_.access_token })
ConvertTo-Json -InputObject $tokens | Set-Content -LiteralPath $tokenPath -Encoding utf8
Write-Host "本地测试数据已准备：用户数 $UserCount；令牌文件已写入忽略目录。"
