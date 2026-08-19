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

    $identityAppPassword = New-LinkVerseSafeSecret
    $identityMigratorPassword = New-LinkVerseSafeSecret
    $tradeAppPassword = New-LinkVerseSafeSecret
    $tradeMigratorPassword = New-LinkVerseSafeSecret
    $paymentAppPassword = New-LinkVerseSafeSecret
    $paymentMigratorPassword = New-LinkVerseSafeSecret
    $tradeClientSecret = New-LinkVerseSafeSecret
    [byte[]]$keyIdBytes = New-LinkVerseRandomBytes -Length 16

    $generatedValues = @{
        MYSQL_ROOT_PASSWORD                  = New-LinkVerseSafeSecret
        IDENTITY_APP_PASSWORD                = $identityAppPassword
        IDENTITY_MIGRATOR_PASSWORD           = $identityMigratorPassword
        TRADE_APP_PASSWORD                   = $tradeAppPassword
        TRADE_MIGRATOR_PASSWORD              = $tradeMigratorPassword
        PAYMENT_APP_PASSWORD                 = $paymentAppPassword
        PAYMENT_MIGRATOR_PASSWORD            = $paymentMigratorPassword
        REDIS_PASSWORD                       = New-LinkVerseSafeSecret
        RABBITMQ_PASSWORD                    = New-LinkVerseSafeSecret
        NACOS_ADMIN_PASSWORD                 = New-LinkVerseSafeSecret
        NACOS_RUNTIME_PASSWORD               = New-LinkVerseSafeSecret
        NACOS_AUTH_TOKEN                     = New-LinkVerseNacosToken
        NACOS_AUTH_IDENTITY_KEY              = New-LinkVerseSafeSecret
        NACOS_AUTH_IDENTITY_VALUE            = New-LinkVerseSafeSecret
        LINKVERSE_IDENTITY_DB_PASSWORD       = $identityAppPassword
        LINKVERSE_IDENTITY_FLYWAY_PASSWORD   = $identityMigratorPassword
        TRADE_DB_PASSWORD                    = $tradeAppPassword
        TRADE_FLYWAY_PASSWORD                = $tradeMigratorPassword
        PAYMENT_DB_PASSWORD                  = $paymentAppPassword
        PAYMENT_FLYWAY_PASSWORD              = $paymentMigratorPassword
        LINKVERSE_JWT_KEY_ID                 = 'linkverse-local-' + [Convert]::ToHexString($keyIdBytes).ToLowerInvariant()
        LINKVERSE_JWT_PRIVATE_KEY            = ([Uri]::new($KeyPaths.PrivateKeyPath)).AbsoluteUri
        LINKVERSE_JWT_PUBLIC_KEY             = ([Uri]::new($KeyPaths.PublicKeyPath)).AbsoluteUri
        LINKVERSE_TRADE_CLIENT_SECRET        = $tradeClientSecret
        TRADE_OAUTH_CLIENT_SECRET            = $tradeClientSecret
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

$context = Get-LinkVerseInfrastructureContext
$secretsDirectory = Join-Path $context.InfrastructureRoot 'secrets'
$keyPaths = Initialize-LinkVerseIdentityKeys -SecretsDirectory $secretsDirectory
$environmentCreated = Initialize-LinkVerseEnvironmentFile `
    -ExamplePath $context.ExampleFile `
    -EnvironmentPath $context.EnvironmentFile `
    -KeyPaths $keyPaths

if ($environmentCreated) {
    Write-Host '已创建忽略的本地 .env 与 RSA 2048 PEM；未输出任何秘密值。'
}
else {
    Write-Host '本地 .env 已存在，未覆盖；Identity RSA 密钥对已完成一致性校验。'
}
