# GitHub Actions 자동 검증

`.github/workflows/ci.yml`을 GitHub에 올리면 GitHub가 준비한 Windows 서버에서 Java 17과 Maven Wrapper로 `verify`를 실행합니다. 개발자 PC를 서버로 제공하거나 켜 둘 필요가 없습니다. 이 작업은 테스트와 JAR 빌드 검증이며, 실행 프로그램 배포·릴리스 공개는 하지 않습니다.

## 처음 한 번 할 일

1. GitHub Desktop에서 `ReciptToExcel` 저장소를 선택합니다.
2. 변경 목록에서 `.github/workflows/ci.yml`, `docs/CI.md`, `README.md`를 확인합니다.
3. 커밋 메시지에 `GitHub Actions CI 추가`를 쓰고 **Commit to master**를 누릅니다.
4. **Push origin**을 누릅니다. PC에서 커밋만 하면 GitHub의 CI는 실행되지 않습니다.
5. 웹에서 저장소의 **Actions → ReceiptToExcel CI → 방금 실행된 항목**을 엽니다.
6. 초록색 체크는 성공, 빨간색 X는 실패입니다. 실행 중에는 진행 표시가 나옵니다.

워크플로 파일이 있으므로 웹에서 Maven 템플릿을 별도로 만들 필요는 없습니다. Actions 사용이 비활성화되어 있으면 저장소의 **Settings → Actions → General**에서 사용을 허용해야 합니다. 조직 저장소라면 조직 정책의 제한이 적용될 수 있습니다.

## 매번 사용하는 방법

코드를 수정한 뒤 GitHub Desktop에서 커밋하고 푸시하면 브랜치별로 실행됩니다. PR을 만들거나 수정해도 실행되며, 같은 대상에 연속으로 올리면 이전 실행은 취소하고 최신 코드를 검사합니다. PR 브랜치의 push와 PR 검증은 별도 실행으로 나타날 수 있습니다.

기본 브랜치에 설정을 올린 뒤에는 **Actions → ReceiptToExcel CI → Run workflow**로 수동 실행할 수도 있습니다. 검사 결과는 해당 커밋 기준이므로, 추가 수정을 푸시했다면 최신 실행 결과를 확인합니다.

## 결과 확인

- 실행 요약에 전체 테스트 건수·실패·오류·생략 건수가 표시됩니다. 현재 120건이며 새 테스트를 추가하면 자동으로 늘어납니다.
- 실패한 실행에서 **Windows / Java 17 → Build and run all tests**를 열면 오류와 실패한 테스트 이름을 볼 수 있습니다.
- **Check Windows OCR script syntax** 단계는 Windows PowerShell 5 문법을 확인합니다. 실제 사진의 OCR를 실행하는 단계는 아닙니다.
- **Artifacts → test-reports-windows-java17**에서 상세 테스트 보고서를 받을 수 있습니다. 보고서는 7일 보관합니다.
- 의존 라이브러리는 Maven 캐시를 사용하므로 다음 실행에서 다시 받는 양을 줄입니다.

## 검증 범위와 로컬 작업

자동 테스트는 규칙 파싱, 승인번호·중복 처리, PDF 등록·렌더링, 검토·기장·취소, 엑셀 출력, 요청 제한 등을 검사합니다. 임시 DB·임시 폴더·합성 이미지와 가짜 OCR/AI 응답을 사용합니다. 로컬에서 확인한 실제 영수증 9건의 OCR 기록 비교는 CI의 120건에 포함되지 않습니다.

따라서 CI 성공은 실제 영수증을 모두 정확히 인식한다는 뜻이 아닙니다. Windows OCR 인식률, 실제 AI 모델의 결과, 배포 EXE 실행·브라우저 동작은 필요한 변경에서 따로 확인합니다. 개인 장부·영수증·서비스 계정 키는 CI에 필요 없으며 저장소에도 올리지 않습니다.

코드 변경 중에는 관련 테스트만 로컬에서 확인하고, 푸시한 코드의 전체 자동 검증은 CI 결과를 활용할 수 있습니다. 오류 수정이나 배포 전 필요한 검증까지 모두 생략하는 것은 아닙니다. CI가 실패하면 코드를 고쳐 다시 커밋·푸시합니다. CI 실패가 이미 푸시한 커밋을 되돌리거나 master 직접 푸시를 차단하지는 않습니다.

## 비용과 배포

공개 저장소의 표준 GitHub 제공 실행 환경은 무료입니다. 비공개 저장소는 계정 요금제의 무료 시간·저장 공간 한도가 적용되므로 GitHub의 사용량 화면을 확인합니다. 개인 PC에 실행기를 설치하는 방식은 사용하지 않습니다.

CI 성공 후에도 GitHub Releases의 ZIP은 자동 갱신되지 않습니다. 배포판 생성·업로드는 기존 절차로 별도 진행합니다.

공식 안내:

- [Maven 빌드와 테스트](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)
- [수동 실행](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
- [실행 결과 확인](https://docs.github.com/en/actions/how-tos/monitor-workflows/use-the-visualization-graph)
- [무료 사용량과 과금](https://docs.github.com/en/actions/concepts/billing-and-usage)
