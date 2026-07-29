param(
  [string]$ContainerName = "ruoyi-ai-backend",
  [string]$OutputPath = "logs\ruoyi-ai.log",
  [int]$Tail = 2000
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $repoRoot $OutputPath
$targetDir = Split-Path -Parent $target
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$exists = docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $ContainerName }
if (-not $exists) {
  throw "Docker container '$ContainerName' not found. Start RuoYi-AI first, or pass -ContainerName."
}

docker logs --tail $Tail $ContainerName 2>&1 | Set-Content -Encoding UTF8 $target
Write-Host "Synced docker logs to $target"
Write-Host "MCP view_logs can read it as serviceName=ruoyi-ai"
