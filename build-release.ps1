param([switch]$SkipTests, [string]$JdkHome)

# 실행 파일·서버·전용 Java를 묶는 개발자용 배포 명령입니다. 결과물은 Java가 없는 PC에서도 실행됩니다.
$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $PSScriptRoot
try {
    if (-not $JdkHome) { $JdkHome = & .\release\prepare-jdk.ps1 }
    $JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
    foreach ($toolName in @('java','javac','jar','jlink','jpackage')) {
        if (-not (Test-Path -LiteralPath (Join-Path $JdkHome "bin\$toolName.exe"))) { throw "Missing JDK tool: $toolName" }
    }
    # Maven에도 같은 빌드용 JDK를 전달합니다. 끝나면 기존 환경 변수로 복원합니다.
    $env:JAVA_HOME = $JdkHome
    [xml]$projectPom = Get-Content -LiteralPath 'pom.xml' -Raw
    $appVersion = [string]$projectPom.project.version
    if ($appVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Use a numeric release version in pom.xml.' }
    if ($SkipTests) { & .\mvnw.cmd -q -DskipTests package }
    else { & .\mvnw.cmd -q verify }
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. Release ZIP was not created.' }

    $packageName = "ReceiptToExcel-$appVersion-windows-x64"
    # 고유한 임시 폴더를 사용하므로 이전 배포 폴더나 사용자 자료를 삭제하지 않습니다.
    $staging = Join-Path $PSScriptRoot ("target\desktop-build-" + [guid]::NewGuid().ToString('N'))
    $inputRoot = Join-Path $staging 'input'
    $classRoot = Join-Path $staging 'launcher-classes'
    New-Item -ItemType Directory -Path $inputRoot,$classRoot | Out-Null
    # 관리 창만 별도 JAR로 만들고 기존 스프링 서버는 그대로 포함합니다.
    & (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 --release 17 -d $classRoot 'src\main\java\kr\local\receipts\desktop\DesktopLauncher.java'
    if ($LASTEXITCODE -ne 0) { throw 'Desktop launcher compilation failed.' }
    & (Join-Path $JdkHome 'bin\jar.exe') --create --file (Join-Path $inputRoot 'desktop-launcher.jar') --main-class kr.local.receipts.desktop.DesktopLauncher -C $classRoot .
    if ($LASTEXITCODE -ne 0) { throw 'Desktop launcher JAR creation failed.' }
    Copy-Item -LiteralPath "target\receipt-to-excel-$appVersion.jar" -Destination (Join-Path $inputRoot 'receipt-to-excel.jar')
    Copy-Item -LiteralPath 'application-local.properties.example' -Destination $inputRoot
    $runtimeRoot = Join-Path $staging 'runtime'
    # OCR·엑셀·HTTPS·한글 로캘과 관리 창의 실행 환경입니다. 자식 서버용 java.exe도 보존합니다.
    & (Join-Path $JdkHome 'bin\jlink.exe') --module-path (Join-Path $JdkHome 'jmods') --add-modules 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.charsets,jdk.localedata,jdk.management,jdk.zipfs' --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtimeRoot
    if ($LASTEXITCODE -ne 0) { throw 'Bundled runtime creation failed.' }
    $imageRoot = Join-Path $staging 'image'
    & (Join-Path $JdkHome 'bin\jpackage.exe') --type app-image --name ReceiptToExcel --app-version $appVersion --vendor ReceiptToExcel --input $inputRoot --main-jar desktop-launcher.jar --main-class kr.local.receipts.desktop.DesktopLauncher --runtime-image $runtimeRoot --java-options '-Dfile.encoding=UTF-8' --dest $imageRoot
    if ($LASTEXITCODE -ne 0) { throw 'Windows executable creation failed.' }
    $packageRoot = Join-Path $imageRoot 'ReceiptToExcel'
    Copy-Item -LiteralPath 'docs\RELEASE.md' -Destination (Join-Path $packageRoot 'README.md')
    New-Item -ItemType Directory -Path (Join-Path $packageRoot 'docs') | Out-Null
    Copy-Item -LiteralPath 'docs\BOOKKEEPING.md','docs\APPROVAL_NUMBER.md' -Destination (Join-Path $packageRoot 'docs')
    # Java 배포판의 고지·버전과 jlink의 runtime/legal을 함께 전달합니다.
    Copy-Item -LiteralPath (Join-Path $JdkHome 'release') -Destination (Join-Path $packageRoot 'runtime-version.txt')
    foreach ($noticeName in @('NOTICE','LICENSE')) {
        $noticePath = Join-Path $JdkHome $noticeName
        if (Test-Path -LiteralPath $noticePath) { Copy-Item -LiteralPath $noticePath -Destination (Join-Path $packageRoot ("RUNTIME-"+$noticeName)) }
    }
    Copy-Item -LiteralPath 'release\RUNTIME-NOTICE.md' -Destination $packageRoot
    $zipPath = Join-Path $PSScriptRoot "target\$packageName.zip"
    Compress-Archive -LiteralPath $packageRoot -DestinationPath $zipPath -Force
    $digest = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$zipPath.sha256", "$digest  $packageName.zip`n", [System.Text.Encoding]::ASCII)
    Set-Content -LiteralPath 'target\desktop-image-path.txt' -Value $packageRoot
    Write-Host "Release ZIP: $zipPath"
    Write-Host "SHA256: $zipPath.sha256"
} finally { $env:JAVA_HOME = $previousJavaHome; Pop-Location }
