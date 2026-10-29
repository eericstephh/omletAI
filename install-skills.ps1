# Installs community + official Android skills into .opencode\skills of the CURRENT folder.
# Run from your Android project root:  powershell -ExecutionPolicy Bypass -File install-skills.ps1
# Requires git. Each skill is copied into a folder named after the `name:` in its SKILL.md.
$ErrorActionPreference = "Stop"
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Write-Host "git is not installed"; exit 1 }

$dest = Join-Path (Get-Location) ".opencode\skills"
New-Item -ItemType Directory -Force $dest | Out-Null
$tmp = Join-Path $env:TEMP ("skills-src-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $tmp | Out-Null

$sources = @(
  # Google official (Apache-2.0). Niche areas are skipped to keep the skill list short.
  @{ repo = "https://github.com/android/skills.git";
     exclude = "[\\/](tv|wear|xr|media|device-ai|play|identity|devtools|profilers)[\\/]"; only = $null },
  # Architecture, Compose, coroutines, Gradle, i18n/RTL, CI/CD
  @{ repo = "https://github.com/Drjacky/claude-android-ninja.git"; exclude = $null; only = $null },
  # Compose code generation + review rules
  @{ repo = "https://github.com/anhvt52/jetpack-compose-skills.git"; exclude = $null; only = $null },
  # General engineering discipline (only 4 skills)
  @{ repo = "https://github.com/obra/superpowers.git"; exclude = $null;
     only = @("systematic-debugging", "verification-before-completion", "requesting-code-review", "receiving-code-review") }
)

$installed = @()
foreach ($s in $sources) {
  $repoName = ($s.repo -split "/")[-1] -replace "\.git$", ""
  $dir = Join-Path $tmp $repoName
  Write-Host "Cloning $repoName ..."
  git clone --depth 1 --quiet $s.repo $dir
  if ($LASTEXITCODE -ne 0) { Write-Host "  failed, skipping"; continue }

  Get-ChildItem $dir -Recurse -Filter "SKILL.md" -File | ForEach-Object {
    $skillDir = $_.Directory.FullName
    $rel = $skillDir.Substring($dir.Length) + "\"
    if ($rel -match "[\\/]\.git[\\/]" -or $rel -match "node_modules") { return }
    if ($s.exclude -and $rel -match $s.exclude) { return }

    $m = Select-String -Path $_.FullName -Pattern '^name:\s*(.+)$' -List
    $name = if ($m) { $m.Matches[0].Groups[1].Value.Trim().Trim('"', "'") } else { $_.Directory.Name }
    if ($s.only -and ($s.only -notcontains $name)) { return }

    $target = Join-Path $dest $name
    if (Test-Path $target) { Write-Host "  skip (already exists): $name"; return }
    New-Item -ItemType Directory -Force $target | Out-Null
    Get-ChildItem $skillDir -Force | Where-Object { $_.Name -ne ".git" } |
      Copy-Item -Destination $target -Recurse -Force
    $installed += $name
  }
}
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ""
Write-Host ("Installed {0} skills:" -f $installed.Count)
$installed | Sort-Object | ForEach-Object { Write-Host "  $_" }
Write-Host ""
Write-Host "Restart opencode so it rescans skills."
