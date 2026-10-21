# IntelliJ 없이 실행할 때 사용하는 PowerShell 진입점입니다. Maven 래퍼가 필요한 실행 환경을 준비합니다.
$ErrorActionPreference = 'Stop'
# DB와 사진의 상대 경로가 실행 위치에 따라 달라지지 않도록 프로젝트 폴더로 이동합니다.
Set-Location -LiteralPath $PSScriptRoot
Write-Host 'ReceiptToExcel: http://127.0.0.1:8080'
Write-Host 'Press Ctrl+C to stop.'
# 이 프로세스가 끝날 때까지 현재 창을 유지합니다. 종료는 Ctrl+C로 요청합니다.
& .\mvnw.cmd spring-boot:run
