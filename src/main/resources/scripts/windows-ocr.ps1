param([Parameter(Mandatory=$true)][string]$ImagePath, [Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
    [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType=WindowsRuntime] | Out-Null
    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
    [Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
    [Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime] | Out-Null
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    function Await-WinRt($operation, $resultType) {
        $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
        $task.Wait()
        return $task.Result
    }
    $languages = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag })
    if ($languages.Count -eq 0) { throw 'No OCR languages installed. Add Korean OCR in Windows language settings.' }
    $engine = $null
    if ($languages -contains 'ko') { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('ko')) }
    if ($languages -contains 'ko-KR') { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('ko-KR')) }
    if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
    if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($languages[0])) }
    $file = Await-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try { $result = Await-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult]) }
        finally { $bitmap.Dispose() }
        # OCR can return an entire label column before the amount column.
        # Reconstruct visual rows from word coordinates before parsing money fields.
        $words = @($result.Lines | ForEach-Object { $_.Words } | ForEach-Object {
            [pscustomobject]@{ Text=$_.Text; Left=$_.BoundingRect.X; Right=($_.BoundingRect.X + $_.BoundingRect.Width); Top=$_.BoundingRect.Y; Bottom=($_.BoundingRect.Y + $_.BoundingRect.Height); Center=($_.BoundingRect.Y + $_.BoundingRect.Height / 2); Height=$_.BoundingRect.Height }
        } | Sort-Object Center, Left)
        $rows = [System.Collections.Generic.List[string]]::new()
        $rowBoxes = [System.Collections.Generic.List[object]]::new()
        function Add-OcrRow($rowWords) {
            $rowText = (($rowWords | Sort-Object Left | ForEach-Object { $_.Text }) -join ' ')
            $rows.Add($rowText)
            $rowBoxes.Add(@{text=$rowText; top=($rowWords | Measure-Object Top -Minimum).Minimum; bottom=($rowWords | Measure-Object Bottom -Maximum).Maximum})
        }
        $group = [System.Collections.Generic.List[object]]::new()
        $center = 0.0
        $height = 0.0
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
$json = ConvertTo-Json -InputObject $payload -Compress -Depth 5
[System.IO.File]::WriteAllText($OutputPath, $json, [System.Text.UTF8Encoding]::new($false))
