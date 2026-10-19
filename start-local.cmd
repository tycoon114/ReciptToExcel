@echo off
cd /d "%~dp0"
echo ReceiptToExcel - local expense ledger
echo Open http://127.0.0.1:8080 after the app starts.
echo Press Ctrl+C to stop.
call mvnw.cmd spring-boot:run
pause
