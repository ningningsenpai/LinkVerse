[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_infrastructure.ps1')

function Write-LinkVerseNewUtf8File {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Content
    )

    $encoding = [Text.UTF8Encoding]::new($false)
    $bytes = $encoding.GetBytes($Content)
    $stream = [IO.FileStream]::new(
        $Path,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    finally {
        $stream.Dispose()
    }
}

function New-LinkVerseRandomBytes {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(16, 256)]
        [int]$Length
    )

    [byte[]]$bytes = New-Object byte[] $Length
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ,$bytes
}

function New-LinkVerseSafeSecret {
    [byte[]]$bytes = New-LinkVerseRandomBytes -Length 32
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function New-LinkVerseNacosToken {
    [byte[]]$bytes = New-LinkVerseRandomBytes -Length 48
    return [Convert]::ToBase64String($bytes)
}

function Test-LinkVerseRsaKeyPair {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PrivateKeyPath,

        [Parameter(Mandatory = $true)]
        [string]$PublicKeyPath
    )

    $privateRsa = [Security.Cryptography.RSA]::Create()
    $publicRsa = [Security.Cryptography.RSA]::Create()
    try {
        $privateRsa.ImportFromPem([IO.File]::ReadAllText($PrivateKeyPath))
        $publicRsa.ImportFromPem([IO.File]::ReadAllText($PublicKeyPath))
        if ($privateRsa.KeySize -lt 2048 -or $publicRsa.KeySize -lt 2048) {
            throw '已有 Identity RSA 密钥小于 2048 位，脚本不会自动覆盖。'
        }

        $privatePublic = $privateRsa.ExportSubjectPublicKeyInfo()
        $publicPublic = $publicRsa.ExportSubjectPublicKeyInfo()
        if (-not [Security.Cryptography.CryptographicOperations]::FixedTimeEquals(
                $privatePublic,
                $publicPublic
            )) {
            throw '已有 Identity 私钥与公钥不匹配，脚本不会自动覆盖。'
        }
    }
    finally {
        $privateRsa.Dispose()
        $publicRsa.Dispose()
    }
}

function Initialize-LinkVerseIdentityKeys {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SecretsDirectory
    )

    if (-not (Test-Path -LiteralPath $SecretsDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $SecretsDirectory | Out-Null
    }

    $privateKeyPath = Join-Path $SecretsDirectory 'identity-private.pem'
    $publicKeyPath = Join-Path $SecretsDirectory 'identity-public.pem'
    $privateKeyExists = Test-Path -LiteralPath $privateKeyPath -PathType Leaf
    $publicKeyExists = Test-Path -LiteralPath $publicKeyPath -PathType Leaf

    if ($publicKeyExists -and -not $privateKeyExists) {
        throw '只发现 Identity 公钥而缺少私钥；为避免生成不匹配密钥，请先显式处理该文件。'
    }

    if (-not $privateKeyExists) {
        $rsa = [Security.Cryptography.RSA]::Create()
        try {
            $rsa.KeySize = 2048
            $privatePem = [Security.Cryptography.PemEncoding]::WriteString(
                'PRIVATE KEY',
                $rsa.ExportPkcs8PrivateKey()
            ) + [Environment]::NewLine
            $publicPem = [Security.Cryptography.PemEncoding]::WriteString(
                'PUBLIC KEY',
                $rsa.ExportSubjectPublicKeyInfo()
            ) + [Environment]::NewLine
            Write-LinkVerseNewUtf8File -Path $privateKeyPath -Content $privatePem
            Write-LinkVerseNewUtf8File -Path $publicKeyPath -Content $publicPem
        }
        finally {
            $rsa.Dispose()
        }
    }
    elseif (-not $publicKeyExists) {
        $rsa = [Security.Cryptography.RSA]::Create()
        try {
            $rsa.ImportFromPem([IO.File]::ReadAllText($privateKeyPath))
            if ($rsa.KeySize -lt 2048) {
                throw 'Identity 私钥小于 2048 位，脚本不会自动覆盖。'
            }
            $publicPem = [Security.Cryptography.PemEncoding]::WriteString(
                'PUBLIC KEY',
                $rsa.ExportSubjectPublicKeyInfo()
            ) + [Environment]::NewLine
            Write-LinkVerseNewUtf8File -Path $publicKeyPath -Content $publicPem
        }
        finally {
            $rsa.Dispose()
        }
    }

    Test-LinkVerseRsaKeyPair -PrivateKeyPath $privateKeyPath -PublicKeyPath $publicKeyPath
    return [PSCustomObject]@{
        PrivateKeyPath = $privateKeyPath
        PublicKeyPath  = $publicKeyPath
    }
}

function Initialize-LinkVerseEnvironmentFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExamplePath,

        [Parameter(Mandatory = $true)]
        [string]$EnvironmentPath,

        [Parameter(Mandatory = $true)]
        [PSCustomObject]$KeyPaths
    )

    if (Test-Path -LiteralPath $EnvironmentPath) {
        return $false
    }

    $middlewarePassword = '123456abc'
    $tradeClientSecret = New-LinkVerseSafeSecret
    $recommendationClientSecret = New-LinkVerseSafeSecret
    [byte[]]$keyIdBytes = New-LinkVerseRandomBytes -Length 16

    $generatedValues = @{
        MYSQL_ROOT_PASSWORD                  = $middlewarePassword
        IDENTITY_APP_PASSWORD                = $middlewarePassword
        IDENTITY_MIGRATOR_PASSWORD           = $middlewarePassword
        TRADE_APP_PASSWORD                   = $middlewarePassword
        TRADE_MIGRATOR_PASSWORD              = $middlewarePassword
        PAYMENT_APP_PASSWORD                 = $middlewarePassword
        PAYMENT_MIGRATOR_PASSWORD            = $middlewarePassword
        REDIS_PASSWORD                       = $middlewarePassword
        RABBITMQ_PASSWORD                    = $middlewarePassword
        PAYMENT_MOCK_HMAC_SECRET             = New-LinkVerseSafeSecret
        NACOS_ADMIN_PASSWORD                 = $middlewarePassword
        NACOS_RUNTIME_PASSWORD               = $middlewarePassword
        NACOS_AUTH_TOKEN                     = New-LinkVerseNacosToken
        NACOS_AUTH_IDENTITY_KEY              = New-LinkVerseSafeSecret
        NACOS_AUTH_IDENTITY_VALUE            = New-LinkVerseSafeSecret
        LINKVERSE_IDENTITY_DB_PASSWORD       = $middlewarePassword
        LINKVERSE_IDENTITY_FLYWAY_PASSWORD   = $middlewarePassword
        TRADE_DB_PASSWORD                    = $middlewarePassword
        TRADE_FLYWAY_PASSWORD                = $middlewarePassword
        PAYMENT_DB_PASSWORD                  = $middlewarePassword
        PAYMENT_FLYWAY_PASSWORD              = $middlewarePassword
        LINKVERSE_JWT_KEY_ID                 = 'linkverse-local-' + [Convert]::ToHexString($keyIdBytes).ToLowerInvariant()
        LINKVERSE_JWT_PRIVATE_KEY            = ([Uri]::new($KeyPaths.PrivateKeyPath)).AbsoluteUri
        LINKVERSE_JWT_PUBLIC_KEY             = ([Uri]::new($KeyPaths.PublicKeyPath)).AbsoluteUri
        LINKVERSE_TRADE_CLIENT_SECRET        = $tradeClientSecret
        TRADE_OAUTH_CLIENT_SECRET            = $tradeClientSecret
        LINKVERSE_RECOMMENDATION_CLIENT_SECRET = $recommendationClientSecret
        RECOMMENDATION_OAUTH_CLIENT_SECRET   = $recommendationClientSecret
        RECOMMENDATION_USER_HMAC_SECRET      = New-LinkVerseSafeSecret
        RECOMMENDATION_MODEL_ROOT            = [IO.Path]::GetFullPath(
            (Join-Path (Split-Path $ExamplePath -Parent) '..\recommendation\models')
        )
    }

    $outputLines = foreach ($line in Get-Content -LiteralPath $ExamplePath) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $name = $Matches[1]
            $value = $Matches[2]
            if ($value.StartsWith('CHANGE_ME')) {
                if (-not $generatedValues.ContainsKey($name)) {
                    throw ".env.example 中的占位符缺少安全生成规则：$name"
                }
                "$name=$($generatedValues[$name])"
                continue
            }
        }
        $line
    }

    $content = ($outputLines -join [Environment]::NewLine) + [Environment]::NewLine
    Write-LinkVerseNewUtf8File -Path $EnvironmentPath -Content $content
    return $true
}

function Update-LinkVerseRecommendationEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EnvironmentPath,

        [Parameter(Mandatory = $true)]
        [string]$ExamplePath
    )

    $lines = @(Get-Content -LiteralPath $EnvironmentPath)
    $existing = @{}
    foreach ($line in $lines) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $existing[$Matches[1]] = $Matches[2]
        }
    }

    $clientSecret = if ($existing.ContainsKey('LINKVERSE_RECOMMENDATION_CLIENT_SECRET')) {
        $existing['LINKVERSE_RECOMMENDATION_CLIENT_SECRET']
    }
    elseif ($existing.ContainsKey('RECOMMENDATION_OAUTH_CLIENT_SECRET')) {
        $existing['RECOMMENDATION_OAUTH_CLIENT_SECRET']
    }
    else {
        New-LinkVerseSafeSecret
    }
    $required = [ordered]@{
        LINKVERSE_RECOMMENDATION_CLIENT_SECRET = $clientSecret
        RECOMMENDATION_OAUTH_CLIENT_SECRET     = $clientSecret
        RECOMMENDATION_USER_HMAC_SECRET        = New-LinkVerseSafeSecret
        RECOMMENDATION_MODEL_ROOT              = [IO.Path]::GetFullPath(
            (Join-Path (Split-Path $ExamplePath -Parent) '..\recommendation\models')
        )
    }
    $missing = @($required.Keys | Where-Object { -not $existing.ContainsKey($_) })
    if ($missing.Count -eq 0) {
        return $false
    }

    $newLines = @($lines) + @('', '# Recommendation 本地配置')
    foreach ($name in $missing) {
        $newLines += "$name=$($required[$name])"
    }
    $content = ($newLines -join [Environment]::NewLine) + [Environment]::NewLine
    $temporaryPath = "$EnvironmentPath.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporaryPath, $content, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporaryPath -Destination $EnvironmentPath -Force
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
    return $true
}

$context = Get-LinkVerseInfrastructureContext
$secretsDirectory = Join-Path $context.InfrastructureRoot 'secrets'
$keyPaths = Initialize-LinkVerseIdentityKeys -SecretsDirectory $secretsDirectory
$environmentCreated = Initialize-LinkVerseEnvironmentFile `
    -ExamplePath $context.ExampleFile `
    -EnvironmentPath $context.EnvironmentFile `
    -KeyPaths $keyPaths
$recommendationEnvironmentUpdated = $false
if (-not $environmentCreated) {
    $recommendationEnvironmentUpdated = Update-LinkVerseRecommendationEnvironment `
        -EnvironmentPath $context.EnvironmentFile `
        -ExamplePath $context.ExampleFile
}

if ($environmentCreated) {
    Write-Host '已创建忽略的本地 .env 与 RSA 2048 PEM；未输出任何秘密值。'
}
else {
    if ($recommendationEnvironmentUpdated) {
        Write-Host '本地 .env 已保留原值并补充缺失的 Recommendation 配置；未输出任何秘密值。'
    }
    else {
        Write-Host '本地 .env 已存在，未覆盖；Identity RSA 密钥对已完成一致性校验。'
    }
}
