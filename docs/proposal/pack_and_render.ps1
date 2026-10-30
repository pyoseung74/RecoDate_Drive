param(
  [string]$SrcDir,    # 압축 풀린 docx 폴더
  [string]$OutDocx,   # 만들 docx
  [string]$PngDir     # 페이지 이미지를 둘 폴더 (빈 값이면 렌더링 안 함)
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# 1) docx로 압축 (경로 구분자는 반드시 '/', [Content_Types].xml을 맨 앞에)
if (Test-Path $OutDocx) { Remove-Item $OutDocx -Force }
$zip = [System.IO.Compression.ZipFile]::Open($OutDocx, [System.IO.Compression.ZipArchiveMode]::Create)
$root = (Resolve-Path $SrcDir).Path.TrimEnd('\')
$files = Get-ChildItem $root -Recurse -File | Sort-Object { if ($_.Name -eq '[Content_Types].xml') { 0 } else { 1 } }, FullName
foreach ($f in $files) {
  $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}
$zip.Dispose()
Write-Output "packed $($files.Count) files -> $OutDocx"

if (-not $PngDir) { return }

# 2) Word로 PDF 저장 (문서가 깨졌으면 여기서 오류)
$pdf = [System.IO.Path]::ChangeExtension($OutDocx, ".pdf")
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
  $doc = $word.Documents.Open($OutDocx, $false, $true)
  $doc.SaveAs2($pdf, 17)
  Write-Output "pages: $($doc.ComputeStatistics(2))"
  $doc.Close($false)
} finally { $word.Quit() }

# 3) PDF 페이지를 PNG로 (Windows 기본 PDF 렌더러)
[void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
[void][Windows.Data.Pdf.PdfDocument, Windows.Data.Pdf, ContentType = WindowsRuntime]
[void][Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
$asTaskAction = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' } | Select-Object -First 1
function Await($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(); $task.Result }
function AwaitAction($op) { $task = $asTaskAction.Invoke($null, @($op)); $task.Wait() }

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($pdf)) ([Windows.Storage.StorageFile])
$pdfDoc = Await ([Windows.Data.Pdf.PdfDocument]::LoadFromFileAsync($file)) ([Windows.Data.Pdf.PdfDocument])
New-Item -ItemType Directory -Force $PngDir | Out-Null
Get-ChildItem $PngDir -Filter "page-*.png" | Remove-Item
for ($i = 0; $i -lt $pdfDoc.PageCount; $i++) {
  $page = $pdfDoc.GetPage($i)
  $stream = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
  $opts = New-Object Windows.Data.Pdf.PdfPageRenderOptions
  $opts.DestinationWidth = 1100
  AwaitAction ($page.RenderToStreamAsync($stream, $opts))
  $netStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream.GetInputStreamAt(0))
  $out = [System.IO.File]::Create((Join-Path $PngDir ("page-{0:D2}.png" -f ($i + 1))))
  $netStream.CopyTo($out); $out.Close(); $netStream.Close(); $page.Dispose()
}
Write-Output "rendered $($pdfDoc.PageCount) pages to $PngDir"
