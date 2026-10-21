@echo off
rem 실행 위치를 프로젝트 폴더로 고정하여 DB와 사진이 같은 data 폴더를 사용하게 합니다.
cd /d "%~dp0"
echo ReceiptToExcel - local expense ledger
echo Open http://127.0.0.1:8080 after the app starts.
echo Press Ctrl+C to stop.
rem Maven 래퍼로 로컬 서버를 실행합니다. 이 창을 유지하고 종료할 때 Ctrl+C를 누릅니다.
call mvnw.cmd spring-boot:run
pause
