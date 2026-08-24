[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('trade', 'payment')][string]$Service,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{32}$')][string]$EventId,
    [switch]$ConfirmReplay
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_delivery.ps1')

Initialize-LinkVerseDeliveryEnvironment | Out-Null
$port = if ($Service -eq 'trade') { 18082 } else { 18083 }
$uri = "http://127.0.0.1:$port/internal/v1/outbox-events/$EventId"
$current = Invoke-LinkVerseJsonRequest -Method Get -Uri $uri
Assert-LinkVerseStatus -Response $current -Expected 200 -Operation '查询 Outbox 事件'
if ($current.Json.status -ne 'PARKED') {
    throw "事件当前状态为 $($current.Json.status)，仅允许重放 PARKED 事件。"
}
if (-not $ConfirmReplay) {
    throw '重放会重新发布原事件；确认根因修复后传入 -ConfirmReplay。'
}
$replayed = Invoke-LinkVerseJsonRequest -Method Post -Uri "$uri/replay"
Assert-LinkVerseStatus -Response $replayed -Expected 200 -Operation '重放 Outbox 事件'
[PSCustomObject]@{
    event_id = $replayed.Json.eventId
    service = $Service
    status = $replayed.Json.status
    attempt_count = $replayed.Json.attemptCount
} | ConvertTo-Json
