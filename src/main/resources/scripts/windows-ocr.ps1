# Java가 임시 사본으로 실행하는 Windows 내장 OCR 연결 스크립트입니다.
# 입력 사진은 읽기만 하며, 인식 원문과 행 좌표를 지정된 JSON 파일에 UTF-8로 기록합니다.
param([Parameter(Mandatory=$true)][string]$ImagePath, [Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
try {
    # WinRT의 저장소·이미지·OCR 비동기 형식을 PowerShell에서 사용할 수 있게 불러옵니다.
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType=WindowsRuntime] | Out-Null
    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
    [Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
    [Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime] | Out-Null
    # WinRT 비동기 결과를 .NET Task로 바꿔 기다릴 제네릭 메서드를 찾아 둡니다.
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    # 호출마다 반환 형식이 달라지므로 결과 형식을 인자로 받아 비동기 작업을 동기적으로 완료합니다.
    function Await-WinRt($operation, $resultType) {
        $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
        $task.Wait()
        return $task.Result
    }
    # 설치된 언어 중 한국어를 우선하고, 없으면 사용자 기본 언어 또는 사용 가능한 첫 언어를 씁니다.
    $languages = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag })
    if ($languages.Count -eq 0) { throw 'No OCR languages installed. Add Korean OCR in Windows language settings.' }
    $engine = $null
    if ($languages -contains 'ko') { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('ko')) }
    if ($languages -contains 'ko-KR') { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('ko-KR')) }
    if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
    if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($languages[0])) }
    # 파일 스트림과 비트맵은 finally에서 해제하여 이후 회전·재등록 때 파일 잠금이 남지 않게 합니다.
    $file = Await-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try { $result = Await-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult]) }
        finally { $bitmap.Dispose() }
        # OCR가 항목 열과 금액 열을 따로 반환할 수 있으므로 원래 행 순서만 믿지 않습니다.
        # 단어 좌표를 기준으로 실제 가로 행을 재구성한 뒤 금액을 해석합니다.
        $words = @($result.Lines | ForEach-Object { $_.Words } | ForEach-Object {
            [pscustomobject]@{ Text=$_.Text; Left=$_.BoundingRect.X; Right=($_.BoundingRect.X + $_.BoundingRect.Width); Top=$_.BoundingRect.Y; Bottom=($_.BoundingRect.Y + $_.BoundingRect.Height); Center=($_.BoundingRect.Y + $_.BoundingRect.Height / 2); Height=$_.BoundingRect.Height }
        } | Sort-Object Center, Left)
        $rows = [System.Collections.Generic.List[string]]::new()
        $rowBoxes = [System.Collections.Generic.List[object]]::new()
        # 같은 시각적 행의 단어를 가로 위치순으로 합치고, Java의 금액 부분 재인식에 필요한 세로 범위를 남깁니다.
        function Add-OcrRow($rowWords) {
            $ordered = @($rowWords | Sort-Object Left)
            $rowText = (($ordered | ForEach-Object { $_.Text }) -join ' ')
            $rows.Add($rowText)
            # 승인번호를 다시 읽을 때 주변 카드번호·금액을 제외할 수 있도록 단어별 위치도 함께 전달합니다.
            $rowBoxes.Add(@{text=$rowText; top=($rowWords | Measure-Object Top -Minimum).Minimum; bottom=($rowWords | Measure-Object Bottom -Maximum).Maximum;
                words=@($ordered | ForEach-Object { @{text=$_.Text; left=$_.Left; right=$_.Right; top=$_.Top; bottom=$_.Bottom} })})
        }
        $group = [System.Collections.Generic.List[object]]::new()
        $center = 0.0
        $height = 0.0
        # 중심 높이 차이가 평균 글자 높이의 허용 범위를 벗어나면 다음 행으로 분리합니다.
        foreach ($word in $words) {
            if ($group.Count -gt 0 -and [Math]::Abs($word.Center - $center) -gt [Math]::Max(6, $height * 0.55)) {
                Add-OcrRow $group
                $group.Clear()
            }
            $group.Add($word)
            $center = ($group | Measure-Object -Property Center -Average).Average
            $height = ($group | Measure-Object -Property Height -Average).Average
        }
        if ($group.Count -gt 0) { Add-OcrRow $group }
        $text = $rows -join "`n"
        $payload = @{text=$text; rows=@($rowBoxes.ToArray()); language=$engine.RecognizerLanguage.LanguageTag; languages=$languages; error=''}
    } finally { $stream.Dispose() }
} catch { $payload = @{text=''; language=''; error=$_.Exception.Message} }
# 성공·실패 모두 JSON으로 기록하므로 Java가 프로세스 출력의 표시 언어나 문자 인코딩에 의존하지 않습니다.
$json = ConvertTo-Json -InputObject $payload -Compress -Depth 5
[System.IO.File]::WriteAllText($OutputPath, $json, [System.Text.UTF8Encoding]::new($false))
