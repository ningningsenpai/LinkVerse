Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:LinkVerseScriptsRoot = $PSScriptRoot

function Get-LinkVerseInfrastructureContext {
    $platformRoot = Split-Path -Parent $script:LinkVerseScriptsRoot
    $infrastructureRoot = Join-Path $platformRoot 'infrastructure'

    [PSCustomObject]@{
        PlatformRoot       = $platformRoot
        InfrastructureRoot = $infrastructureRoot
        ComposeFile        = Join-Path $infrastructureRoot 'compose/compose.yml'
        VersionsFile       = Join-Path $infrastructureRoot 'versions.env'
        EnvironmentFile    = Join-Path $infrastructureRoot '.env'
        ExampleFile        = Join-Path $infrastructureRoot '.env.example'
    }
}

function Import-LinkVerseEnvironmentFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "环境文件不存在：$Path"
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }

        $separatorIndex = $line.IndexOf('=')
        if ($separatorIndex -lt 1) {
            throw "环境文件存在无效行：$rawLine"
        }

        $key = $line.Substring(0, $separatorIndex).Trim()
        $value = $line.Substring($separatorIndex + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
}

function Import-LinkVerseInfrastructureEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject]$Context,

        [Parameter(Mandatory = $true)]
        [string]$EnvironmentFile
    )

    Import-LinkVerseEnvironmentFile -Path $EnvironmentFile
    # 版本文件后加载，防止本地 .env 无意覆盖已冻结的镜像、端口和资源名。
    Import-LinkVerseEnvironmentFile -Path $Context.VersionsFile
}

function Get-LinkVerseEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Assert-LinkVerseCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少必需命令：$Name"
    }
}

function Test-LinkVerseInfrastructureSecrets {
    $requiredSecretNames = @(
        'MYSQL_ROOT_PASSWORD',
        'IDENTITY_APP_PASSWORD',
        'IDENTITY_MIGRATOR_PASSWORD',
        'TRADE_APP_PASSWORD',
        'TRADE_MIGRATOR_PASSWORD',
        'PAYMENT_APP_PASSWORD',
        'PAYMENT_MIGRATOR_PASSWORD',
        'REDIS_PASSWORD',
        'RABBITMQ_PASSWORD',
        'NACOS_ADMIN_PASSWORD',
        'NACOS_RUNTIME_USERNAME',
        'NACOS_RUNTIME_PASSWORD',
        'NACOS_AUTH_TOKEN',
        'NACOS_AUTH_IDENTITY_KEY',
        'NACOS_AUTH_IDENTITY_VALUE'
    )

    foreach ($name in $requiredSecretNames) {
        $value = Get-LinkVerseEnvironmentValue -Name $name
        if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('CHANGE_ME')) {
            throw "请在 infrastructure/.env 中设置真实的本地值：$name"
        }
    }

    $restrictedPasswordNames = @(
        'IDENTITY_APP_PASSWORD',
        'IDENTITY_MIGRATOR_PASSWORD',
        'TRADE_APP_PASSWORD',
        'TRADE_MIGRATOR_PASSWORD',
        'PAYMENT_APP_PASSWORD',
        'PAYMENT_MIGRATOR_PASSWORD',
        'NACOS_RUNTIME_PASSWORD'
    )
    foreach ($name in $restrictedPasswordNames) {
        $value = Get-LinkVerseEnvironmentValue -Name $name
        if ($value -notmatch '^[A-Za-z0-9_@%+=:,.-]{16,128}$') {
            throw "$name 必须为 16～128 位安全字符，且不得包含引号、空格或反斜杠。"
        }
    }

    try {
        $tokenBytes = [Convert]::FromBase64String((Get-LinkVerseEnvironmentValue -Name 'NACOS_AUTH_TOKEN'))
    }
    catch {
        throw 'NACOS_AUTH_TOKEN 必须是有效的 Base64 字符串。'
    }
    if ($tokenBytes.Length -lt 32) {
        throw 'NACOS_AUTH_TOKEN 解码后必须至少包含 32 个随机字节。'
    }

    $runtimeUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_USERNAME'
    if ($runtimeUsername -notmatch '^[A-Za-z0-9_.-]{3,64}$') {
        throw 'NACOS_RUNTIME_USERNAME 只能包含 3～64 位字母、数字、下划线、点或连字符。'
    }
    $adminUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_USERNAME'
    if ($runtimeUsername.Equals($adminUsername, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'NACOS_RUNTIME_USERNAME 不得使用 Nacos 管理员账号。'
    }
}

function Invoke-LinkVerseCompose {
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject]$Context,

        [Parameter(Mandatory = $true)]
        [string]$EnvironmentFile,

        [Parameter(Mandatory = $true)]
        [string[]]$ComposeArguments
    )

    $dockerArguments = @(
        'compose',
        '--env-file', $EnvironmentFile,
        '--env-file', $Context.VersionsFile,
        '--file', $Context.ComposeFile
    ) + $ComposeArguments

    & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose 命令失败，退出码：$LASTEXITCODE"
    }
}

function Assert-LinkVerseNacosResult {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Response,

        [Parameter(Mandatory = $true)]
        [string]$Operation
    )

    $codeProperty = $Response.PSObject.Properties['code']
    if ($null -eq $codeProperty) {
        throw "$Operation 返回了无法识别的 Nacos 响应。"
    }

    $code = [int]$codeProperty.Value
    if ($code -ne 0 -and $code -ne 200) {
        $messageProperty = $Response.PSObject.Properties['message']
        $message = if ($null -eq $messageProperty) { '无详细信息' } else { [string]$messageProperty.Value }
        throw "$Operation 失败，Nacos 状态码 $code：$message"
    }
}

function Get-LinkVerseNacosResultData {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Response,

        [Parameter(Mandatory = $true)]
        [string]$Operation
    )

    Assert-LinkVerseNacosResult -Response $Response -Operation $Operation
    $dataProperty = $Response.PSObject.Properties['data']
    if ($null -eq $dataProperty) {
        throw "$Operation 响应缺少 data 字段。"
    }
    return $dataProperty.Value
}

function Get-LinkVerseNacosPageItems {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Response,

        [Parameter(Mandatory = $true)]
        [string]$Operation
    )

    $data = Get-LinkVerseNacosResultData -Response $Response -Operation $Operation
    if ($null -eq $data) {
        return @()
    }

    $itemsProperty = $data.PSObject.Properties['pageItems']
    if ($null -eq $itemsProperty) {
        throw "$Operation 响应缺少 pageItems 字段。"
    }
    return @($itemsProperty.Value)
}

function Get-LinkVerseNacosAccessToken {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Username,

        [Parameter(Mandatory = $true)]
        [string]$Password
    )

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $loginUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/user/login"

    try {
        $response = Invoke-RestMethod -Method Post -Uri $loginUri `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ username = $Username; password = $Password } `
            -TimeoutSec 10
    }
    catch {
        return $null
    }

    $directToken = $response.PSObject.Properties['accessToken']
    if ($null -ne $directToken -and -not [string]::IsNullOrWhiteSpace([string]$directToken.Value)) {
        return [string]$directToken.Value
    }

    $dataProperty = $response.PSObject.Properties['data']
    if ($null -ne $dataProperty -and $null -ne $dataProperty.Value) {
        $nestedToken = $dataProperty.Value.PSObject.Properties['accessToken']
        if ($null -ne $nestedToken -and -not [string]::IsNullOrWhiteSpace([string]$nestedToken.Value)) {
            return [string]$nestedToken.Value
        }
    }

    return $null
}

function Test-LinkVerseNacosPermissionExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Token,

        [Parameter(Mandatory = $true)]
        [string]$Role,

        [Parameter(Mandatory = $true)]
        [string]$Resource,

        [Parameter(Mandatory = $true)]
        [ValidateSet('r', 'w', 'rw')]
        [string]$Action
    )

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $headers = @{ Authorization = "Bearer $Token" }
    $encodedRole = [Uri]::EscapeDataString($Role)
    $encodedResource = [Uri]::EscapeDataString($Resource)
    $encodedAction = [Uri]::EscapeDataString($Action)
    $queryUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/permission?role=$encodedRole&resource=$encodedResource&action=$encodedAction"
    $queryResponse = Invoke-RestMethod -Method Get -Uri $queryUri -Headers $headers -TimeoutSec 10
    return [bool](Get-LinkVerseNacosResultData -Response $queryResponse `
        -Operation "查询 Nacos 权限 $Resource")
}

function Add-LinkVerseNacosPermissionIfMissing {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Token,

        [Parameter(Mandatory = $true)]
        [string]$Role,

        [Parameter(Mandatory = $true)]
        [string]$Resource,

        [Parameter(Mandatory = $true)]
        [ValidateSet('r', 'w', 'rw')]
        [string]$Action
    )

    if (Test-LinkVerseNacosPermissionExists -Token $Token -Role $Role -Resource $Resource -Action $Action) {
        return
    }

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $headers = @{ Authorization = "Bearer $Token" }
    $createUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/permission"
    $createResponse = Invoke-RestMethod -Method Post -Uri $createUri -Headers $headers `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ role = $Role; resource = $Resource; action = $Action } `
        -TimeoutSec 10
    Assert-LinkVerseNacosResult -Response $createResponse -Operation "创建 Nacos 权限 $Resource"
}

function Remove-LinkVerseNacosPermissionIfPresent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Token,

        [Parameter(Mandatory = $true)]
        [string]$Role,

        [Parameter(Mandatory = $true)]
        [string]$Resource,

        [Parameter(Mandatory = $true)]
        [ValidateSet('r', 'w', 'rw')]
        [string]$Action
    )

    if (-not (Test-LinkVerseNacosPermissionExists -Token $Token -Role $Role `
            -Resource $Resource -Action $Action)) {
        return
    }

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $encodedRole = [Uri]::EscapeDataString($Role)
    $encodedResource = [Uri]::EscapeDataString($Resource)
    $encodedAction = [Uri]::EscapeDataString($Action)
    $deleteUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/permission?role=$encodedRole&resource=$encodedResource&action=$encodedAction"
    $deleteResponse = Invoke-RestMethod -Method Delete -Uri $deleteUri `
        -Headers @{ Authorization = "Bearer $Token" } `
        -TimeoutSec 10
    Assert-LinkVerseNacosResult -Response $deleteResponse -Operation "移除 Nacos 越界权限 $Resource"
}

function Assert-LinkVerseNacosRuntimePermissionSet {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Token,

        [Parameter(Mandatory = $true)]
        [string]$Role,

        [Parameter(Mandatory = $true)]
        [string[]]$ExpectedResources
    )

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $encodedRole = [Uri]::EscapeDataString($Role)
    $permissionListUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/permission/list?pageNo=1&pageSize=100&role=$encodedRole&search=accurate"
    $permissionListResponse = Invoke-RestMethod -Method Get -Uri $permissionListUri `
        -Headers @{ Authorization = "Bearer $Token" } `
        -TimeoutSec 10
    $permissionItems = @(Get-LinkVerseNacosPageItems -Response $permissionListResponse `
        -Operation '验证 Nacos runtime 权限集')
    foreach ($expectedResource in $ExpectedResources) {
        $expectedItems = @(
            $permissionItems | Where-Object {
                [string]$_.resource -eq $expectedResource -and [string]$_.action -eq 'rw'
            }
        )
        if ($expectedItems.Count -eq 0) {
            throw "Nacos runtime 角色缺少 naming 读写权限：$expectedResource"
        }
    }

    $unexpectedItems = @(
        $permissionItems | Where-Object {
            [string]$_.resource -notin $ExpectedResources -or [string]$_.action -ne 'rw'
        }
    )
    if ($unexpectedItems.Count -gt 0) {
        $unexpectedSummary = $unexpectedItems | ForEach-Object {
            "$([string]$_.resource) [$([string]$_.action)]"
        }
        throw "Nacos runtime 角色存在超出 discovery-only 边界的权限：$($unexpectedSummary -join ', ')"
    }
}

function Initialize-LinkVerseNacosRuntimeAccount {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AdminToken
    )

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $runtimeUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_USERNAME'
    $runtimePassword = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_PASSWORD'
    $runtimeRole = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_ROLE'
    $headers = @{ Authorization = "Bearer $AdminToken" }

    $encodedUsername = [Uri]::EscapeDataString($runtimeUsername)
    $userListUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/user/list?pageNo=1&pageSize=10&username=$encodedUsername&search=accurate"
    $userListResponse = Invoke-RestMethod -Method Get -Uri $userListUri -Headers $headers -TimeoutSec 10
    $userItems = @(Get-LinkVerseNacosPageItems -Response $userListResponse -Operation '查询 Nacos runtime 账号')
    $userExists = @(
        $userItems | Where-Object { [string]$_.username -eq $runtimeUsername }
    ).Count -gt 0

    $userUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/user"
    if ($userExists) {
        $userResponse = Invoke-RestMethod -Method Put -Uri $userUri -Headers $headers `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ username = $runtimeUsername; newPassword = $runtimePassword } `
            -TimeoutSec 10
        Assert-LinkVerseNacosResult -Response $userResponse -Operation '更新 Nacos runtime 账号密码'
    }
    else {
        $userResponse = Invoke-RestMethod -Method Post -Uri $userUri -Headers $headers `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ username = $runtimeUsername; password = $runtimePassword } `
            -TimeoutSec 10
        Assert-LinkVerseNacosResult -Response $userResponse -Operation '创建 Nacos runtime 账号'
    }

    $roleListUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/role/list?pageNo=1&pageSize=100&username=$encodedUsername&role=&search=accurate"
    $roleListResponse = Invoke-RestMethod -Method Get -Uri $roleListUri -Headers $headers -TimeoutSec 10
    $roleItems = @(Get-LinkVerseNacosPageItems -Response $roleListResponse -Operation '查询 Nacos runtime 角色')
    if (@($roleItems | Where-Object { [string]$_.role -eq 'ROLE_ADMIN' }).Count -gt 0) {
        throw 'Nacos runtime 账号已被授予 ROLE_ADMIN，请先人工移除越权角色再重试。'
    }
    $unexpectedRoles = @($roleItems | Where-Object { [string]$_.role -ne $runtimeRole })
    if ($unexpectedRoles.Count -gt 0) {
        $unexpectedRoleNames = $unexpectedRoles | ForEach-Object { [string]$_.role }
        throw "Nacos runtime 账号存在超出预期的角色：$($unexpectedRoleNames -join ', ')"
    }
    $roleExists = @(
        $roleItems | Where-Object {
            [string]$_.username -eq $runtimeUsername -and [string]$_.role -eq $runtimeRole
        }
    ).Count -gt 0

    if (-not $roleExists) {
        $roleUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/role"
        $roleResponse = Invoke-RestMethod -Method Post -Uri $roleUri -Headers $headers `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ role = $runtimeRole; username = $runtimeUsername } `
            -TimeoutSec 10
        Assert-LinkVerseNacosResult -Response $roleResponse -Operation '绑定 Nacos runtime 角色'
    }

    $namespaceId = Get-LinkVerseEnvironmentValue -Name 'NACOS_NAMESPACE'
    $group = Get-LinkVerseEnvironmentValue -Name 'NACOS_GROUP'
    $serviceNames = @(
        'linkverse-gateway',
        'linkverse-identity',
        'linkverse-trade',
        'linkverse-payment'
    )
    $namingResources = @(
        $serviceNames | ForEach-Object { "${namespaceId}:${group}:naming/$_" }
    )
    foreach ($namingResource in $namingResources) {
        Add-LinkVerseNacosPermissionIfMissing -Token $AdminToken -Role $runtimeRole `
            -Resource $namingResource -Action 'rw'
    }
    foreach ($action in @('r', 'w', 'rw')) {
        Remove-LinkVerseNacosPermissionIfPresent -Token $AdminToken -Role $runtimeRole `
            -Resource "${namespaceId}:${group}:naming/*" -Action $action
    }
    $configResource = "${namespaceId}:${group}:config/*"
    foreach ($action in @('r', 'w', 'rw')) {
        Remove-LinkVerseNacosPermissionIfPresent -Token $AdminToken -Role $runtimeRole `
            -Resource $configResource -Action $action
    }
    Assert-LinkVerseNacosRuntimePermissionSet -Token $AdminToken -Role $runtimeRole `
        -ExpectedResources $namingResources

    $runtimeToken = Get-LinkVerseNacosAccessToken -Username $runtimeUsername -Password $runtimePassword
    if ([string]::IsNullOrWhiteSpace($runtimeToken)) {
        throw 'Nacos runtime 账号初始化后仍无法登录。'
    }
}

function Initialize-LinkVerseNacos {
    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $adminUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_USERNAME'
    $adminPassword = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_PASSWORD'
    $token = Get-LinkVerseNacosAccessToken -Username $adminUsername -Password $adminPassword

    if ([string]::IsNullOrWhiteSpace($token)) {
        $initializeUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/user/admin"
        try {
            Invoke-RestMethod -Method Post -Uri $initializeUri `
                -ContentType 'application/x-www-form-urlencoded' `
                -Body @{ password = $adminPassword } `
                -TimeoutSec 10 | Out-Null
        }
        catch {
            # 初始化接口在管理员已存在时会拒绝请求，随后再次登录才能区分密码不匹配。
        }

        $token = Get-LinkVerseNacosAccessToken -Username $adminUsername -Password $adminPassword
        if ([string]::IsNullOrWhiteSpace($token)) {
            throw '无法登录 Nacos。若复用了旧数据卷，请确认 .env 中的管理员密码与该数据卷一致。'
        }
    }

    $namespaceId = Get-LinkVerseEnvironmentValue -Name 'NACOS_NAMESPACE'
    $encodedNamespaceId = [Uri]::EscapeDataString($namespaceId)
    $headers = @{ Authorization = "Bearer $token" }
    $namespaceUri = "http://127.0.0.1:$serverPort/nacos/v3/admin/core/namespace?namespaceId=$encodedNamespaceId"
    # Nacos 以 HTTP 400 + code=22001 表示 namespace 不存在，需保留响应体才能幂等创建。
    $namespaceResponse = Invoke-RestMethod -Method Get -Uri $namespaceUri -Headers $headers `
        -SkipHttpErrorCheck -TimeoutSec 10

    $namespaceExists = $false
    $namespaceCodeProperty = $namespaceResponse.PSObject.Properties['code']
    $namespaceCode = if ($null -eq $namespaceCodeProperty) { -1 } else { [int]$namespaceCodeProperty.Value }
    if ($namespaceCode -eq 0 -or $namespaceCode -eq 200) {
        $dataProperty = $namespaceResponse.PSObject.Properties['data']
        if ($null -ne $dataProperty -and $null -ne $dataProperty.Value) {
            $namespaceProperty = $dataProperty.Value.PSObject.Properties['namespace']
            $namespaceExists = $null -ne $namespaceProperty -and [string]$namespaceProperty.Value -eq $namespaceId
        }
    }
    elseif ($namespaceCode -ne 22001) {
        Assert-LinkVerseNacosResult -Response $namespaceResponse -Operation '查询 Nacos namespace'
    }

    if (-not $namespaceExists) {
        $createUri = "http://127.0.0.1:$serverPort/nacos/v3/admin/core/namespace"
        $createResponse = Invoke-RestMethod -Method Post -Uri $createUri -Headers $headers `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{
                namespaceId   = $namespaceId
                namespaceName = $namespaceId
                namespaceDesc = 'LinkVerse 求职 MVP 隔离命名空间'
            } `
            -TimeoutSec 10

        Assert-LinkVerseNacosResult -Response $createResponse -Operation '创建 Nacos namespace'
    }

    Initialize-LinkVerseNacosRuntimeAccount -AdminToken $token

    return $token
}

function Test-LinkVerseNacosNamespace {
    $adminUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_USERNAME'
    $adminPassword = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_PASSWORD'
    $token = Get-LinkVerseNacosAccessToken -Username $adminUsername -Password $adminPassword
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw 'Nacos 登录验证失败。'
    }

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $namespaceId = Get-LinkVerseEnvironmentValue -Name 'NACOS_NAMESPACE'
    $encodedNamespaceId = [Uri]::EscapeDataString($namespaceId)
    $uri = "http://127.0.0.1:$serverPort/nacos/v3/admin/core/namespace?namespaceId=$encodedNamespaceId"
    $response = Invoke-RestMethod -Method Get -Uri $uri `
        -Headers @{ Authorization = "Bearer $token" } `
        -TimeoutSec 10

    $dataProperty = $response.PSObject.Properties['data']
    if ($null -eq $dataProperty -or $null -eq $dataProperty.Value) {
        throw "Nacos namespace 不存在：$namespaceId"
    }
    $namespaceProperty = $dataProperty.Value.PSObject.Properties['namespace']
    if ($null -eq $namespaceProperty -or [string]$namespaceProperty.Value -ne $namespaceId) {
        throw "Nacos namespace 校验失败：$namespaceId"
    }
}

function Test-LinkVerseNacosRuntimeLogin {
    $runtimeUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_USERNAME'
    $runtimePassword = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_PASSWORD'
    $runtimeRole = Get-LinkVerseEnvironmentValue -Name 'NACOS_RUNTIME_ROLE'
    $token = Get-LinkVerseNacosAccessToken -Username $runtimeUsername -Password $runtimePassword
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Nacos runtime 账号登录验证失败：$runtimeUsername"
    }

    $adminUsername = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_USERNAME'
    $adminPassword = Get-LinkVerseEnvironmentValue -Name 'NACOS_ADMIN_PASSWORD'
    $adminToken = Get-LinkVerseNacosAccessToken -Username $adminUsername -Password $adminPassword
    if ([string]::IsNullOrWhiteSpace($adminToken)) {
        throw 'Nacos 管理员登录失败，无法验证 runtime 授权。'
    }

    $serverPort = Get-LinkVerseEnvironmentValue -Name 'NACOS_SERVER_HOST_PORT'
    $encodedUsername = [Uri]::EscapeDataString($runtimeUsername)
    $roleListUri = "http://127.0.0.1:$serverPort/nacos/v3/auth/role/list?pageNo=1&pageSize=100&username=$encodedUsername&role=&search=accurate"
    $roleListResponse = Invoke-RestMethod -Method Get -Uri $roleListUri `
        -Headers @{ Authorization = "Bearer $adminToken" } `
        -TimeoutSec 10
    $roleItems = @(Get-LinkVerseNacosPageItems -Response $roleListResponse -Operation '验证 Nacos runtime 角色')
    if (@($roleItems | Where-Object { [string]$_.role -eq 'ROLE_ADMIN' }).Count -gt 0) {
        throw 'Nacos runtime 账号不得拥有 ROLE_ADMIN。'
    }
    $unexpectedRoles = @($roleItems | Where-Object { [string]$_.role -ne $runtimeRole })
    if ($unexpectedRoles.Count -gt 0) {
        $unexpectedRoleNames = $unexpectedRoles | ForEach-Object { [string]$_.role }
        throw "Nacos runtime 账号存在超出预期的角色：$($unexpectedRoleNames -join ', ')"
    }
    if (@($roleItems | Where-Object { [string]$_.role -eq $runtimeRole }).Count -eq 0) {
        throw "Nacos runtime 账号缺少角色：$runtimeRole"
    }

    $namespaceId = Get-LinkVerseEnvironmentValue -Name 'NACOS_NAMESPACE'
    $group = Get-LinkVerseEnvironmentValue -Name 'NACOS_GROUP'
    $expectedResources = @(
        'linkverse-gateway',
        'linkverse-identity',
        'linkverse-trade',
        'linkverse-payment'
    ) | ForEach-Object { "${namespaceId}:${group}:naming/$_" }
    Assert-LinkVerseNacosRuntimePermissionSet -Token $adminToken -Role $runtimeRole `
        -ExpectedResources $expectedResources
}
