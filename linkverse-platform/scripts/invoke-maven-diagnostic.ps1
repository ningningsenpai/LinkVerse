[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]*$')]
    [string]$Module,
    [Parameter(Mandatory = $true)]
    [ValidateSet('build', 'test')]
    [string]$Category,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]*$')]
    [string]$LogName,
    [string]$JavaHome = 'D:\Java JDK\jdk-21.0.12+8',
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$MavenArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

$context = Get-LinkVerseDeliveryContext
Initialize-LinkVerseJavaEnvironment -JavaHome $JavaHome
if ($Module -ne 'backend') {
    $moduleRoot = Join-Path $context.BackendRoot $Module
    if (-not (Test-Path -LiteralPath $moduleRoot -PathType Container)) {
        throw "后端模块不存在：$Module"
    }
}

$logDirectory = Initialize-LinkVerseLogDirectory -Context $context `
    -Module $Module -Category $Category
$logFileName = $LogName.EndsWith('.log', [StringComparison]::OrdinalIgnoreCase) `
    ? $LogName : "$LogName.log"
$logPath = Join-Path $logDirectory $logFileName

Push-Location $context.BackendRoot
try {
    & (Join-Path $context.BackendRoot 'mvnw.cmd') @MavenArguments 2>&1 |
        Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    throw "Maven 诊断命令失败，退出码：$exitCode；日志：$logPath"
}
Write-Host "Maven 诊断命令执行成功，日志：$logPath"
