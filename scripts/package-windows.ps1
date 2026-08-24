$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $RootDir

foreach ($Command in @("java", "mvn", "jpackage")) {
    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        throw "$Command bulunamadı. JDK 17+ ve Maven kurulu olmalı."
    }
}

$JavaVersionLine = ((& java -version 2>&1 | Select-Object -First 1) -join "")
if ($JavaVersionLine -notmatch '"(?:(1)\.)?([0-9]+)') { throw "Java sürümü okunamadı: $JavaVersionLine" }
$JavaMajor = [int]$Matches[2]
if ($JavaMajor -lt 21) { throw "Dağıtım paketi için tam JDK 21 veya üzeri gerekli (bulunan: Java $JavaMajor)." }

mvn -B clean package
if ($LASTEXITCODE -ne 0) { throw "Maven derlemesi başarısız." }

Copy-Item -Force (Join-Path $RootDir "target\AeroMC.jar") (Join-Path $RootDir "target\package-input\AeroMC.jar")
Copy-Item -Force (Join-Path $RootDir "LICENSE.txt") (Join-Path $RootDir "target\package-input\LICENSE.txt")
Copy-Item -Force (Join-Path $RootDir "THIRD-PARTY-NOTICES.md") (Join-Path $RootDir "target\package-input\THIRD-PARTY-NOTICES.md")
[xml]$Pom = Get-Content (Join-Path $RootDir "pom.xml")
$Version = $Pom.project.version

$OutputDir = Join-Path $RootDir "release\windows"
$ExpectedOutput = [System.IO.Path]::GetFullPath((Join-Path $RootDir "release\windows"))
if ([System.IO.Path]::GetFullPath($OutputDir) -ne $ExpectedOutput) { throw "Güvensiz çıktı yolu." }
if (Test-Path $OutputDir) { Remove-Item -Recurse -Force $OutputDir }
New-Item -ItemType Directory -Force $OutputDir | Out-Null

& jpackage `
  --type exe `
  --dest $OutputDir `
  --input (Join-Path $RootDir "target\package-input") `
  --name AeroMC `
  --app-version $Version `
  --vendor "The Aero Group" `
  --copyright "Copyright (c) 2026 The Aero Group" `
  --description "Cross-platform Minecraft server management panel" `
  --main-jar AeroMC.jar `
  --main-class com.aerogroup.mcpanel.Launcher `
  --icon (Join-Path $RootDir "packaging\icons\aeromc.ico") `
  --jlink-options "--strip-debug --no-man-pages --no-header-files" `
  --java-options "-Dfile.encoding=UTF-8" `
  --license-file (Join-Path $RootDir "LICENSE.txt") `
  --win-menu `
  --win-menu-group AeroMC `
  --win-shortcut `
  --win-dir-chooser `
  --win-per-user-install `
  --win-upgrade-uuid "5f67f9e4-00a8-4f98-bcb4-553147df6462"

if ($LASTEXITCODE -ne 0) { throw "Windows paketi oluşturulamadı." }
$Installer = Get-ChildItem -Path $OutputDir -Filter "*.exe" | Select-Object -First 1
if (-not $Installer) { throw "Windows kurulum dosyası bulunamadı." }
$Hash = (Get-FileHash -Algorithm SHA256 $Installer.FullName).Hash.ToLowerInvariant()
"$Hash  $($Installer.Name)" | Set-Content -Encoding ascii (Join-Path $OutputDir "SHA256SUMS.txt")
"$Hash  $($Installer.Name)" | Set-Content -Encoding ascii (Join-Path $OutputDir "$($Installer.Name).sha256")
Write-Host "Windows paketi: $OutputDir"
