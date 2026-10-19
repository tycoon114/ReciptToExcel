# Stops only the preview process started by Codex, after verifying its exact JAR path.
$ErrorActionPreference = 'Stop'
$receiptPidFile = Join-Path $PSScriptRoot 'data\preview.pid'
if (-not (Test-Path -LiteralPath $receiptPidFile)) { Write-Host 'No Codex preview process recorded.'; exit }
$receiptProcessId = [int](Get-Content -LiteralPath $receiptPidFile -Raw).Trim()
$receiptJar = Join-Path $PSScriptRoot 'target\receipt-to-excel-0.1.0.jar'
$receiptProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $receiptProcessId"
if ($receiptProcess -and $receiptProcess.CommandLine -and $receiptProcess.CommandLine.Contains($receiptJar)) {
    Stop-Process -Id $receiptProcessId
    Write-Host 'Codex preview stopped. You can now run ReceiptApplication in IntelliJ.'
} else { Write-Host 'Preview already stopped. No other process was changed.' }
