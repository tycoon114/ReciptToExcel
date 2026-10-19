$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
Write-Host 'ReceiptToExcel: http://127.0.0.1:8080'
Write-Host 'Press Ctrl+C to stop.'
& .\mvnw.cmd spring-boot:run
