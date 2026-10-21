# Codex가 기록한 미리보기 프로세스만 종료합니다. PID뿐 아니라 해당 프로젝트 JAR 경로도 확인합니다.
$ErrorActionPreference = 'Stop'
$receiptPidFile = Join-Path $PSScriptRoot 'data\preview.pid'
if (-not (Test-Path -LiteralPath $receiptPidFile)) { Write-Host 'No Codex preview process recorded.'; exit }
# PID가 재사용되었을 가능성이 있으므로 이 값만으로 프로세스를 종료하지 않습니다.
$receiptProcessId = [int](Get-Content -LiteralPath $receiptPidFile -Raw).Trim()
$receiptJar = Join-Path $PSScriptRoot 'target\receipt-to-excel-0.1.0.jar'
$receiptProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $receiptProcessId"
# IntelliJ나 다른 프로젝트의 Java 프로세스를 잘못 종료하지 않도록 명령줄 경로가 일치할 때만 중지합니다.
if ($receiptProcess -and $receiptProcess.CommandLine -and $receiptProcess.CommandLine.Contains($receiptJar)) {
    Stop-Process -Id $receiptProcessId
    Write-Host 'Codex preview stopped. You can now run ReceiptApplication in IntelliJ.'
} else { Write-Host 'Preview already stopped. No other process was changed.' }
