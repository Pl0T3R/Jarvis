<#
  JARVIS - zbiera log z gogli do pliku jarvis-log.txt (w folderze repozytorium).

  Użycie (w folderze repozytorium, gogle podłączone kablem):
      powershell -ExecutionPolicy Bypass -File .\log.ps1

  Log zawiera to, co JARVIS usłyszał i powiedział. Klucza API w nim nie ma.
#>

$ErrorActionPreference = "Stop"
$pkg = "com.pl0t3r.jarvis"
. (Join-Path $PSScriptRoot "tools\adb.ps1")

$adb = Find-Adb | Select-Object -Last 1
if (-not $adb -or -not (Test-Path $adb)) { throw "Nie udało się znaleźć ani pobrać adb.exe" }
& $adb start-server | Out-Null
if (-not ((& $adb devices) | Select-String "`tdevice$")) {
    Write-Host "Nie widzę gogli. Podłącz je kablem i zaakceptuj 'Allow USB debugging' w goglach."
    exit 1
}

$out = Join-Path $PSScriptRoot "jarvis-log.txt"
& $adb logcat -c

Write-Host ""
Write-Host "Teraz w goglach:"
Write-Host " 1. otwórz panel JARVIS i kliknij 'Test głosu',"
Write-Host " 2. powiedz: 'JARVIS, która godzina?',"
Write-Host " 3. poczekaj, aż JARVIS skończy mówić (także drugi raz, jeśli powtórzy)."
Write-Host ""
Read-Host "Potem naciśnij tutaj Enter"

$lines = @()
$lines += "=== wersja ==="
$lines += (& $adb shell dumpsys package $pkg) | Select-String "versionName|versionCode|lastUpdateTime" | ForEach-Object { $_.Line.Trim() }
$lines += ""
$lines += "=== procesy ==="
$lines += (& $adb shell ps -A) | Select-String "jarvis" | ForEach-Object { $_.Line }
$lines += ""
$lines += "=== przesyłanie obrazu (casting) ==="
$lines += (& $adb shell dumpsys media_projection) | Select-Object -First 15
$lines += ""
$lines += "=== JARVIS ==="
$lines += & $adb logcat -d -v time -s Jarvis
$lines += ""
$lines += "=== dźwięk ==="
$lines += (& $adb logcat -d -v time) | Select-String "(?i)com\.pl0t3r\.jarvis|AudioTrack|AudioFlinger" | Select-Object -Last 300 | ForEach-Object { $_.Line }

$lines | Set-Content -Path $out -Encoding UTF8
Write-Host ""
Write-Host "Zapisane: $out"
Write-Host "Przeciągnij ten plik do czatu z Claude (albo otwórz go i wklej treść)."
