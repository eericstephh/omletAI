<#
.SYNOPSIS
  Builds a static (no-execution) inventory of an Android artifact for reverse-engineering work.

.DESCRIPTION
  Accepts a single APK or a bundle container (.apks / .apkm / .xapk / .zip holding base + splits),
  extracts it, and writes a reproducible inventory that later steps can drill into:

    summary.md          counts, hashes, dex totals, keyword hit overview
    inventory.txt       full entry list per apk (name, compressed/uncompressed size, sha256)
    manifest.xml        decoded base manifest
    packages.txt        every class/method defined in the base dex set (apkanalyzer)
    keywords.txt        class/method/field rows matching the feature keywords
    libs.txt            native libraries with ABI, size and sha256
    assets.txt          asset entries with extension, size and sha256

  Nothing in the artifact is executed. No code, asset or model weight is copied into the
  repository: everything lands under -OutDir (default: build/capcut-re, git-ignored build output).

.EXAMPLE
  pwsh -File scripts\capcut_re_inventory.ps1 -Apk "C:\Users\me\Downloads\capcut.apkm"
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$Apk,

  [string]$OutDir = (Join-Path $PSScriptRoot '..\build\capcut-re'),

  [string[]]$Keywords = @(
    'matt', 'segment', 'portrait', 'cutout', 'bgremov', 'background_remov',
    'backgroundremov', 'rmbg', 'smartcut', 'foreground', 'alphamatte', 'chromakey'
  )
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

# DEX string pools survive obfuscation, so they are the most reliable feature index
# when class and method names are renamed.
if (-not ('ReaStringExtract' -as [type])) {
  Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public static class ReaStringExtract
{
    public static int Extract(string inputPath, string outputPath, int minLength)
    {
        byte[] bytes = File.ReadAllBytes(inputPath);
        var seen = new HashSet<string>(StringComparer.Ordinal);
        int written = 0;
        using (var writer = new StreamWriter(outputPath, false, new UTF8Encoding(false)))
        {
            var buffer = new StringBuilder(512);
            for (int i = 0; i < bytes.Length; i++)
            {
                byte b = bytes[i];
                if (b >= 0x20 && b <= 0x7E)
                {
                    buffer.Append((char)b);
                    continue;
                }
                Flush(buffer, seen, writer, minLength, ref written);
            }
            Flush(buffer, seen, writer, minLength, ref written);
        }
        return written;
    }

    private static void Flush(StringBuilder buffer, HashSet<string> seen, StreamWriter writer, int minLength, ref int written)
    {
        if (buffer.Length >= minLength)
        {
            string value = buffer.ToString();
            if (seen.Add(value))
            {
                writer.WriteLine(value);
                written++;
            }
        }
        buffer.Clear();
    }
}
'@
}

# ---------------------------------------------------------------- toolchain ---
$BuildTools = 'C:\android\build-tools\36.0.0'
$CmdlineTools = 'C:\android\cmdline-tools\latest\bin'
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$ApkAnalyzer = Join-Path $CmdlineTools 'apkanalyzer.bat'

foreach ($tool in @($Aapt2, $ApkAnalyzer)) {
  if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Users\eric steph\.jdks\jbr-21.0.11')) {
  $env:JAVA_HOME = 'C:\Users\eric steph\.jdks\jbr-21.0.11'
}

function Write-Step([string]$Message) {
  Write-Host ''
  Write-Host "== $Message"
}

function Get-Sha256([string]$Path) {
  (Get-FileHash -Path $Path -Algorithm SHA256).Hash
}

function Expand-Zip([string]$ZipPath, [string]$Destination) {
  New-Item -ItemType Directory -Force -Path $Destination | Out-Null
  [System.IO.Compression.ZipFile]::ExtractToDirectory($ZipPath, $Destination)
}

function Get-EntryRows([string]$ApkPath) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
  try {
    foreach ($entry in $zip.Entries) {
      [pscustomobject]@{
        Apk        = Split-Path $ApkPath -Leaf
        Name       = $entry.FullName
        Compressed = $entry.CompressedLength
        Size       = $entry.Length
      }
    }
  } finally {
    $zip.Dispose()
  }
}

function Get-FileSha256([string]$Path) {
  $stream = [System.IO.File]::OpenRead($Path)
  try {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
      ($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally { $sha.Dispose() }
  } finally { $stream.Dispose() }
}

# ------------------------------------------------------------------- inputs ---
$InputPath = (Resolve-Path -LiteralPath $Apk).Path
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# A directory is what an adb pull of an installed split package produces.
$isDirectory = Test-Path -LiteralPath $InputPath -PathType Container
if ($isDirectory) {
  $source = $InputPath
  $sourceBytes = (Get-ChildItem -Path $InputPath -Recurse -Filter *.apk -File | Measure-Object Length -Sum).Sum
} else {
  $source = Get-Item -LiteralPath $InputPath
  $sourceBytes = $source.Length
}

Write-Step "Source artifact"
$sourceInfo = [pscustomobject]@{
  Path   = $InputPath
  Kind   = $(if ($isDirectory) { 'directory (pulled package)' } else { 'file' })
  Bytes  = $sourceBytes
  Sha256 = $(if ($isDirectory) { '(per apk, see below)' } else { (Get-Sha256 $InputPath) })
}
$sourceInfo | Format-List | Out-String | Write-Host

if ($isDirectory) {
  $innerApks = @(Get-ChildItem -Path $InputPath -Recurse -Filter *.apk -File | Sort-Object Name)
  $bundleDir = $InputPath
} else {
  # A bundle is a zip whose payload is other apks; a plain apk is analyzed in place.
  $bundleDir = Join-Path $OutDir 'bundle'
  $probe = [System.IO.Compression.ZipFile]::OpenRead($InputPath)
  try {
    $hasNestedApks = @($probe.Entries | Where-Object { $_.FullName -match '\.apk$' }).Count -gt 0
  } finally { $probe.Dispose() }

  if ($hasNestedApks) {
    if (Test-Path $bundleDir) { Remove-Item -LiteralPath $bundleDir -Recurse -Force }
    Expand-Zip $InputPath $bundleDir
    $innerApks = @(Get-ChildItem -Path $bundleDir -Recurse -Filter *.apk -File | Sort-Object Name)
  } else {
    $innerApks = @()
  }
}

if ($innerApks.Count -gt 0) {
  Write-Step "Payload: $($innerApks.Count) apk file(s)"
  foreach ($inner in $innerApks) {
    Write-Host ("  {0}  {1:N0} bytes  {2}" -f $inner.Name, $inner.Length, (Get-Sha256 $inner.FullName))
  }
  $apks = $innerApks
} else {
  Write-Step "Single apk (no nested payload)"
  $apks = @(Get-Item -LiteralPath $InputPath)
}

$baseApk = $apks | Where-Object { $_.Name -eq 'base.apk' } | Select-Object -First 1
if (-not $baseApk) {
  # No split naming convention: the largest dex-bearing apk is the best base candidate.
  $baseApk = $apks | Sort-Object Length -Descending | Select-Object -First 1
}
Write-Step "Base candidate: $($baseApk.Name)"
if (-not $isDirectory -and $innerApks.Count -eq 0 -and $baseApk.FullName -ne $InputPath) {
  Write-Host ("  sha256 {0}" -f (Get-Sha256 $baseApk.FullName))
}

# ---------------------------------------------------------------- inventory ---
Write-Step 'Entry inventory'
$allRows = foreach ($apkFile in $apks) { Get-EntryRows $apkFile.FullName }
$allRows | Export-Csv -Path (Join-Path $OutDir 'inventory.csv') -NoTypeInformation -Encoding utf8
$allRows | Sort-Object Apk, Name | Format-Table -AutoSize | Out-String -Width 200 |
  Set-Content -Path (Join-Path $OutDir 'inventory.txt') -Encoding utf8
Write-Host ("  {0} entries from {1} apk file(s)" -f $allRows.Count, $apks.Count)

# ------------------------------------------------------------------ manifest ---
Write-Step 'Decoded manifest (base)'
$manifestPath = Join-Path $OutDir 'manifest.xml'
& $Aapt2 dump xmltree --file AndroidManifest.xml $baseApk.FullName 2>&1 |
  Set-Content -Path $manifestPath -Encoding utf8
Write-Host "  -> manifest.xml ($(((Get-Content $manifestPath).Count)) lines)"

$badgingPath = Join-Path $OutDir 'badging.txt'
& $Aapt2 dump badging $baseApk.FullName 2>&1 | Set-Content -Path $badgingPath -Encoding utf8
Select-String -Path $badgingPath -Pattern '^(package|sdkVersion|targetSdkVersion|application-label|native-code|launchable-activity)' |
  ForEach-Object { Write-Host ("  " + $_.Line) }

# ----------------------------------------------------------------- dex/classes ---
Write-Step 'Dex inventory and class index (base)'
$dexRows = $allRows | Where-Object { $_.Name -match '^classes\d*\.dex$' }
$dexRows | Group-Object Apk | ForEach-Object {
  Write-Host ("  {0}: {1} dex" -f $_.Name, $_.Count)
}

$packagesPath = Join-Path $OutDir 'packages.txt'
& $ApkAnalyzer dex packages $baseApk.FullName 2>&1 | Set-Content -Path $packagesPath -Encoding utf8
$classCount = (Select-String -Path $packagesPath -Pattern '^C d ').Count
$methodCount = (Select-String -Path $packagesPath -Pattern '^M d ').Count
Write-Host ("  -> packages.txt: {0} classes, {1} methods defined" -f $classCount, $methodCount)

Write-Step 'Feature keyword hits'
$keywordHits = foreach ($kw in $Keywords) {
  $matches = Select-String -Path $packagesPath -Pattern $kw -SimpleMatch
  if ($matches) {
    [pscustomobject]@{ Keyword = $kw; Hits = $matches.Count }
  }
}
if ($keywordHits) { $keywordHits | Sort-Object Hits -Descending | Format-Table -AutoSize | Out-String | Write-Host }

$keywordPath = Join-Path $OutDir 'keywords.txt'
$keywordLines = foreach ($kw in $Keywords) {
  "### keyword: $kw"
  Select-String -Path $packagesPath -Pattern $kw -SimpleMatch |
    Select-Object -First 200 |
    ForEach-Object { $_.Line }
  ''
}
$keywordLines | Set-Content -Path $keywordPath -Encoding utf8
Write-Host "  -> keywords.txt"

# ------------------------------------------------------------- dex strings ---
Write-Step 'Dex string pools'
$dexDir = Join-Path $OutDir 'dex'
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
$stringIndexes = @()
foreach ($row in $dexRows) {
  $apkFile = $apks | Where-Object { $_.Name -eq $row.Apk } | Select-Object -First 1
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apkFile.FullName)
  try {
    $entry = $zip.GetEntry($row.Name)
    $dexOut = Join-Path $dexDir (($row.Apk -replace '\.apk$', '') + '-' + $row.Name)
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dexOut, $true)
  } finally { $zip.Dispose() }

  $stringsOut = Join-Path $OutDir ('dexstrings-' + (Split-Path $dexOut -Leaf) + '.txt')
  $count = [ReaStringExtract]::Extract($dexOut, $stringsOut, 6)
  Write-Host ("  {0}: {1:N0} unique strings -> {2}" -f (Split-Path $dexOut -Leaf), $count, (Split-Path $stringsOut -Leaf))
  $stringIndexes += $stringsOut
}

$dexKeywordPath = Join-Path $OutDir 'dexstrings-keywords.txt'
$dexKeywordLines = foreach ($kw in $Keywords) {
  "### keyword: $kw"
  foreach ($index in $stringIndexes) {
    Select-String -Path $index -Pattern $kw -SimpleMatch |
      Select-Object -First 60 |
      ForEach-Object { "$(Split-Path $index -Leaf): $($_.Line)" }
  }
  ''
}
$dexKeywordLines | Set-Content -Path $dexKeywordPath -Encoding utf8
Write-Host "  -> dexstrings-keywords.txt"

# --------------------------------------------------------------- native libs ---
Write-Step 'Native libraries'
$libRows = $allRows | Where-Object { $_.Name -match '^lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/.+\.so$' }
$libReport = foreach ($row in $libRows) {
  $apkFile = $apks | Where-Object { $_.Name -eq $row.Apk } | Select-Object -First 1
  $zip = [System.IO.Compression.ZipFile]::OpenRead($apkFile.FullName)
  try {
    $entry = $zip.GetEntry($row.Name)
    $temp = Join-Path $env:TEMP ("libhash_" + [guid]::NewGuid().ToString('n'))
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $temp, $true)
    try {
      [pscustomobject]@{
        Apk = $row.Apk
        Abi = ($row.Name -split '/')[1]
        Name = Split-Path $row.Name -Leaf
        Bytes = $row.Size
        Sha256 = (Get-FileSha256 $temp)
      }
    } finally { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
  } finally { $zip.Dispose() }
}
$libReport | Sort-Object Abi, Name | Format-Table -AutoSize | Out-String -Width 200 |
  Set-Content -Path (Join-Path $OutDir 'libs.txt') -Encoding utf8
Write-Host ("  {0} .so entries" -f @($libReport).Count)
$libReport | Group-Object Abi | ForEach-Object {
  Write-Host ("  {0}: {1} libs, {2:N1} MB uncompressed" -f $_.Name, $_.Count, (($_.Group | Measure-Object Bytes -Sum).Sum / 1MB))
}

# ------------------------------------------------------------------- assets ---
Write-Step 'Model / asset candidates'
$modelExtensions = @('.tflite', '.onnx', '.mnn', '.bin', '.dat', '.param', '.model', '.pt', '.pb', '.ve', '.json', '.nb', '.so')
$assetRows = $allRows | Where-Object {
  ($_.Name -match '^assets/' -or $_.Name -match '\.(tflite|onnx|mnn|pt|nb)$') -and
  ($modelExtensions -contains [System.IO.Path]::GetExtension($_.Name).ToLowerInvariant())
}
$assetReport = foreach ($row in $assetRows) {
  [pscustomobject]@{
    Apk  = $row.Apk
    Name = $row.Name
    Ext  = [System.IO.Path]::GetExtension($row.Name).ToLowerInvariant()
    Bytes = $row.Size
  }
}
$assetReport | Sort-Object Bytes -Descending | Format-Table -AutoSize | Out-String -Width 200 |
  Set-Content -Path (Join-Path $OutDir 'assets.txt') -Encoding utf8
Write-Host ("  {0} asset entries (listed in assets.txt)" -f @($assetReport).Count)

$modelLike = $assetReport | Where-Object { $_.Ext -in @('.tflite', '.onnx', '.mnn', '.pt', '.nb', '.ve', '.model', '.param') }
if ($modelLike) {
  Write-Host '  model-like files:'
  $modelLike | Sort-Object Bytes -Descending | Select-Object -First 20 | ForEach-Object {
    Write-Host ("    {0,-60} {1,12:N0} bytes  ({2})" -f $_.Name, $_.Bytes, $_.Apk)
  }
}

# ------------------------------------------------------------------ summary ---
Write-Step 'Summary'
$summary = @(
  "# Static inventory: $(Split-Path $InputPath -Leaf)",
  '',
  "- source: ``$InputPath``",
  "- source sha256: ``$($sourceInfo.Sha256)``",
  "- source bytes: $($sourceInfo.Bytes)",
  "- apk files analyzed: $($apks.Count)",
  "- bundle payload: $(if ($innerApks.Count -gt 0) { 'yes' } else { 'no' })",
  "- base apk: ``$($baseApk.Name)``",
  "- entries total: $($allRows.Count)",
  "- dex files: $($dexRows.Count)",
  "- classes defined: $classCount",
  "- methods defined: $methodCount",
  "- native libs: $(@($libReport).Count)",
  "- asset entries: $(@($assetReport).Count)",
  "- dex string indexes: $($stringIndexes.Count)",
  '',
  '## Keyword hits',
  ''
)
if ($keywordHits) {
  $summary += ($keywordHits | Sort-Object Hits -Descending | ForEach-Object { "- $($_.Keyword): $($_.Hits)" })
} else {
  $summary += '- none'
}
$summary | Set-Content -Path (Join-Path $OutDir 'summary.md') -Encoding utf8
Write-Host "  -> $OutDir"
