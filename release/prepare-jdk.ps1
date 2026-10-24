param([switch]$Refresh)
# 빌드용 Temurin을 프로젝트의 무시되는 작업 폴더에 준비합니다. PC 전체 Java 설정은 변경하지 않습니다.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$cacheRoot = Join-Path $repoRoot 'work\packaging-jdk'
$homeRecord = Join-Path $cacheRoot 'current-home.txt'
if (-not $Refresh -and (Test-Path -LiteralPath $homeRecord)) {
    $cachedHome = (Get-Content -LiteralPath $homeRecord -Raw).Trim()
    if (Test-Path -LiteralPath (Join-Path $cachedHome 'bin\jpackage.exe')) { return $cachedHome }
}
New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$metadata = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')[0]
if (-not $metadata.binary.package.link.StartsWith('https://github.com/adoptium/temurin17-binaries/')) { throw 'Unexpected JDK download source.' }
$archive = Join-Path $cacheRoot $metadata.binary.package.name
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri $metadata.binary.package.link -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $metadata.binary.package.checksum) {
    throw 'Temurin SHA256 mismatch. Remove the damaged archive from work/packaging-jdk and try again.'
}
Expand-Archive -LiteralPath $archive -DestinationPath $cacheRoot -Force
$jdkHome = Join-Path $cacheRoot $metadata.release_name
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\jpackage.exe'))) { throw 'JDK packaging tools were not found.' }
$metadata | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $cacheRoot 'metadata.json')
Set-Content -LiteralPath $homeRecord -Value $jdkHome
return $jdkHome
