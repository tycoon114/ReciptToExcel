# ReceiptToExcel 0.3.1 · Java 설치 없는 Windows 실행 안내

0.2.0 이후의 PDF 등록·인식 보완·자동 검증 변경 사항은 배포판과 프로젝트의 `docs/RELEASE_NOTES.md`에 정리되어 있습니다.

## 사용자가 실행하는 방법

지원 대상은 Windows 10/11 **x64 PC**입니다. 이 ZIP에는 전용 Java 실행 환경이 포함되어 있으므로 Java 설치, JAVA_HOME 설정, IntelliJ, Maven이 필요하지 않습니다.

1. GitHub 릴리스의 Assets에서 **ReceiptToExcel-0.3.1-windows-x64.zip**을 받습니다.
2. 문서 폴더 등 쓰기 가능한 위치에 **압축을 모두 풉니다**. ZIP 안에서 바로 실행하지 않습니다.
3. 폴더 안의 **ReceiptToExcel.exe**를 더블 클릭합니다. 관리자 권한은 필요하지 않습니다.
4. 작은 **영수증 장부 · 실행 관리** 창이 나타나고 준비가 끝나면 기본 브라우저로 장부가 열립니다.
5. 브라우저를 닫았다면 관리 창의 **장부 열기**로 다시 엽니다.
6. 사용을 마치면 관리 창의 **프로그램 종료** 또는 ×를 누릅니다. 서버가 DB를 정리한 뒤 관리 창도 닫힙니다.

브라우저만 닫으면 프로그램은 계속 실행됩니다. 기본 브라우저를 열지 못하면 관리 창에 표시된 주소를 직접 입력하세요.
기본 포트 8080이 사용 중이면 빈 포트를 선택하므로 실제 접속 주소는 관리 창에서 확인합니다.
이미 실행 중인 상태에서 EXE를 다시 누르면 새 서버를 만들지 않고 기존 장부를 엽니다.

EXE 파일만 떼어 전달하지 마세요. **app·runtime 폴더를 포함한 전체 폴더**가 필요합니다. 실행 환경을 동봉한 휴대용 ZIP이며 설치 마법사나 단일 EXE 파일 형식은 아닙니다.

## 데이터 저장·설정·백업

장부는 압축을 푼 프로그램 폴더와 분리하여 **%LOCALAPPDATA%\ReceiptToExcel**에 저장됩니다. 관리 창의 **데이터 폴더 열기**를 누르면 바로 열 수 있습니다.

- DB: data/ledger.mv.db
- 증빙 사진: data/images
- 업로드한 PDF 원본: data/pdf
- 거래처 보정 사전: data/merchant-rules.json
- 선택 설정: application-local.properties
- 로그: logs/receipt-server.log, logs/desktop-launcher.log

백업할 때는 프로그램을 정상 종료한 후 이 사용자 데이터 폴더 전체를 복사하세요. 새 버전은 프로그램 폴더만 교체하면 기존 사용자 장부를 계속 사용합니다.
각 PC의 장부는 서로 독립적이며 자동 공유되지 않습니다. 개발자 PC의 실제 장부·사진·개인 설정·서비스 계정 키는 배포 ZIP에 포함하지 않습니다.

기존 IntelliJ/0.1.0 장부를 이어 쓰려면 양쪽 앱을 모두 종료하고 두 data 폴더를 각각 백업한 뒤, 기존 프로젝트의 **data 폴더 전체**를 새 사용자 데이터 폴더 안에 복사합니다. 이미 새 장부를 입력했다면 덮어쓰기 전에 반드시 백업하세요. 자동 병합 기능은 없습니다.
Google/Ollama를 설정했다면 application-local.properties와 직접 보관한 인증키도 별도로 옮기고 파일 경로를 다시 확인합니다. 개발용 서버와 배포판은 기본 DB 위치가 다릅니다.

선택 설정은 사용자 데이터 폴더에 만들어진 application-local.properties.example을 application-local.properties로 복사하여 작성합니다. 포트·DB·사진 경로·관리 API 설정은 실행 관리자가 고정하므로 이 파일에서 바꾸지 않습니다.

## OCR·선택 기능

Java는 설치할 필요가 없습니다. 기본 문자 인식은 **Windows 내장 OCR**를 사용하므로 한국어 OCR 언어 기능은 필요합니다. 없는 PC에서는 Windows 설정에서 한국어 OCR을 추가해야 합니다. 이는 Java 설치와 별개입니다.
기본 OCR·장부·기장·엑셀은 오프라인으로 사용할 수 있습니다. 구글 시트 직접 연결에는 인터넷과 사용자의 계정 설정이 필요하고, 선택적 Ollama 모드는 별도 모델 설치가 필요합니다.
승인번호 등 인식 결과는 원본과 비교한 뒤 확정하세요. 기능 상세는 docs 폴더의 안내에 있습니다. 연속 스캔 PDF는 한 페이지에 영수증 한 장을 기준으로 최대 25MB·20쪽을 지원합니다.

## GitHub 릴리스에 올리기

초록색 Code → Download ZIP은 **개발용 소스 코드**입니다. 실행 파일과 Java 실행 환경이 포함되어 있지 않습니다. 고객에게는 아래에서 만든 실행용 ZIP을 전달하세요.

1. GitHub Desktop에서 소스 변경 사항을 확인하고 Commit → Push origin 합니다.
2. 프로젝트 터미널에서 아래 명령으로 테스트·패키징합니다.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build-release.ps1

3. 결과물 target/ReceiptToExcel-0.3.1-windows-x64.zip과 .zip.sha256을 확인합니다.
4. GitHub 웹사이트에서 Releases → Draft a new release를 엽니다.
5. 태그 v0.3.1과 이번 코드를 Push한 브랜치를 선택하고 제목·변경 내용을 작성합니다.
6. 실행용 ZIP과 SHA256 파일을 Assets에 첨부합니다. 첫 고객 검증 배포라면 pre-release로 등록합니다.
7. Publish release 후 고객에게 릴리스 페이지 주소를 전달합니다.

릴리스는 파일 배포 기능입니다. GitHub에서 서버를 실행하는 것이 아니라 고객의 PC에서 EXE를 실행하는 방식입니다.

## 개발자용 패키징 구조

빌드 스크립트는 처음에 공식 Adoptium API에서 Temurin JDK 17 Windows x64 ZIP을 받아 SHA256을 검증하고 work/packaging-jdk에 준비합니다. PC 전체 Java 설치나 환경 변수 설정은 바꾸지 않습니다. 이후에는 캐시를 사용합니다.
최신 패치를 다시 준비하려면 release/prepare-jdk.ps1 -Refresh를 실행한 뒤 빌드합니다. 이미 검증한 같은 소스는 build-release.ps1 -SkipTests로 패키지만 다시 만들 수 있습니다. 버전은 pom.xml의 프로젝트 버전에서 읽습니다.

jlink로 전용 실행 환경을 만들고 jpackage의 app-image로 Windows 실행 파일을 만듭니다. 별도 WiX 설치는 필요하지 않습니다. Java 고지·라이선스는 runtime/legal, runtime-version.txt, RUNTIME-NOTICE 자료에 포함합니다.
관리 창과 스프링 서버는 분리되어 있습니다. 사용자 폴더 잠금으로 중복 실행을 막고 실행별 인증값으로 해당 서버만 확인·종료합니다. 일반 IntelliJ 실행에서는 관리 API가 활성화되지 않습니다.
검증용 옵션 --data-home=별도경로, --no-browser, --port=0을 제공하며 --quit은 같은 데이터 경로의 실행을 정상 종료합니다. 고객은 옵션 없이 EXE를 더블 클릭하면 됩니다.

공식 참고:
- https://docs.oracle.com/en/java/javase/17/jpackage/packaging-overview.html
- https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository
