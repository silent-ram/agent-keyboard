param(
    [string]$SourceRoot = (Join-Path $PSScriptRoot "..\android\app\build\pinyin-sources"),
    [string]$CedictArchivePath
)

$ErrorActionPreference = "Stop"
$cedictSha256 = "82652957FA7C459ECC7B0D5E3F4CC2E3C0C96A29F950E7192BAAE31575469C94"
$thuoclCommit = "a30ce79d895d01ab5132a5c74c29703ff7efb4cc"
$thuoclUrl = "https://github.com/thunlp/THUOCL/archive/$thuoclCommit.zip"
$thuoclSha256 = "0C710AEAF9B75B345C944C3C4F625BB4C8135EE97518DC5BE5F444BF5ED1F89B"
$jiebaCommit = "67fa2e36e72f69d9134b8a1037b83fbb070b9775"
$jiebaUrl = "https://raw.githubusercontent.com/fxsjy/jieba/$jiebaCommit/jieba/dict.txt"
$jiebaSha256 = "7197C3211DDD98962B036CDF40324D1EA2BFAA12BD028E68FAA70111A88E12A8"

$sourceRoot = [System.IO.Path]::GetFullPath($SourceRoot)
$null = New-Item -ItemType Directory -Force -Path $sourceRoot
$cedictArchive = Join-Path $sourceRoot "cedict.txt.gz"
$cedictFile = Join-Path $sourceRoot "cedict.txt"
$thuoclArchive = Join-Path $sourceRoot "thuocl.zip"
$thuoclExtract = Join-Path $sourceRoot "thuocl-$thuoclCommit"
$jiebaDictionary = Join-Path $sourceRoot "jieba-dict-$jiebaCommit.txt"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

$cedictArchivePath = if ([string]::IsNullOrWhiteSpace($CedictArchivePath)) {
    $cedictArchive
} else {
    [System.IO.Path]::GetFullPath($CedictArchivePath)
}
if (-not (Test-Path -LiteralPath $cedictArchivePath -PathType Leaf)) {
    throw "Provide a CC-CEDICT gzip obtained through an authorized method: -CedictArchivePath <file>. Expected SHA-256: $cedictSha256"
}
$cedictArchive = $cedictArchivePath
$actualHash = (Get-FileHash -LiteralPath $cedictArchive -Algorithm SHA256).Hash
if ($actualHash -ne $cedictSha256) {
    throw "CC-CEDICT source hash mismatch: $actualHash"
}

$jiebaHash = $null
if (Test-Path -LiteralPath $jiebaDictionary) {
    $jiebaHash = (Get-FileHash -LiteralPath $jiebaDictionary -Algorithm SHA256).Hash
}
if ($jiebaHash -ne $jiebaSha256) {
    Invoke-WebRequest -Uri $jiebaUrl -OutFile $jiebaDictionary
    $jiebaHash = (Get-FileHash -LiteralPath $jiebaDictionary -Algorithm SHA256).Hash
}
if ($jiebaHash -ne $jiebaSha256) {
    throw "Jieba dictionary source hash mismatch: $jiebaHash"
}

$inputStream = [System.IO.File]::OpenRead($cedictArchive)
try {
    $gzipStream = [System.IO.Compression.GzipStream]::new(
        $inputStream,
        [System.IO.Compression.CompressionMode]::Decompress
    )
    try {
        $outputStream = [System.IO.File]::Create($cedictFile)
        try {
            $gzipStream.CopyTo($outputStream)
        } finally {
            $outputStream.Dispose()
        }
    } finally {
        $gzipStream.Dispose()
    }
} finally {
    $inputStream.Dispose()
}

Invoke-WebRequest -Uri $thuoclUrl -OutFile $thuoclArchive
$thuoclActualHash = (Get-FileHash -LiteralPath $thuoclArchive -Algorithm SHA256).Hash
if ($thuoclActualHash -ne $thuoclSha256) {
    throw "THUOCL source hash mismatch: $thuoclActualHash"
}
$null = New-Item -ItemType Directory -Force -Path $thuoclExtract
Expand-Archive -LiteralPath $thuoclArchive -DestinationPath $thuoclExtract -Force
$thuoclData = Get-ChildItem -LiteralPath $thuoclExtract -Directory |
    ForEach-Object { Join-Path $_.FullName "data" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if (-not $thuoclData) {
    throw "Could not locate THUOCL data directory"
}

$outputFile = Join-Path $repoRoot "android\app\src\main\assets\pinyin_dict.txt"
node (Join-Path $PSScriptRoot "generate_pinyin_dict.mjs") `
    --cedict $cedictFile `
    --thuocl $thuoclData `
    --jieba $jiebaDictionary `
    --output $outputFile
