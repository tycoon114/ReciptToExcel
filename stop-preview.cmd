@echo off
rem 프로세스 확인과 종료는 같은 폴더의 PowerShell 스크립트에 위임합니다.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-preview.ps1"
pause
