<#
  JARVIS - instalacja na Meta Quest 3 z komputera z Windows (PowerShell).

  Wymagania: gogle w trybie dewelopera, podłączone kablem USB-C do przesyłu danych,
  w goglach zaakceptowane "Allow USB debugging".

  Użycie (w folderze repozytorium):
      powershell -ExecutionPolicy Bypass -File .\install.ps1
      powershell -ExecutionPolicy Bypass -File .\install.ps1 -Apk sciezka\do\jarvis.apk
#>
param([string]$Apk = (Join-Path $PSScriptRoot "dist\jarvis.apk"))

$ErrorActionPreference = "Stop"
$pkg = "com.pl0t3r.jarvis"

function Find-Adb {
    # only a real adb.exe on PATH: an alias or wrapper named adb has no file path and can't be run with &
    $cmd = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source -and (Test-Path $cmd.Source)) { return $cmd.Source }
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Meta Quest Developer Hub\resources\bin\adb.exe",
        "$PSScriptRoot\tools\platform-tools\adb.exe"
    )
    $candidates += Get-ChildItem "$env:ProgramFiles\Unity\Hub\Editor\*\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }

    Write-Host "Nie znalazłem adb. Pobieram Android Platform Tools od Google..."
    $zip = Join-Path $env:TEMP "platform-tools.zip"
    Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
    Expand-Archive $zip -DestinationPath (Join-Path $PSScriptRoot "tools") -Force
    return "$PSScriptRoot\tools\platform-tools\adb.exe"
}

function Adb { & $script:adb @args; if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') nie powiodło się" } }

if (-not (Test-Path $Apk)) { throw "Nie ma pliku $Apk" }
$script:adb = Find-Adb | Select-Object -Last 1
if (-not $script:adb -or -not (Test-Path $script:adb)) { throw "Nie udało się znaleźć ani pobrać adb.exe" }
Write-Host "adb: $adb"

& $adb start-server | Out-Null
$devices = (& $adb devices) | Select-String "`tdevice$"
if (-not $devices) {
    Write-Host ""
    Write-Host "Nie widzę gogli. Sprawdź:"
    Write-Host " 1. kabel USB-C do danych (nie tylko do ładowania),"
    Write-Host " 2. tryb dewelopera włączony w aplikacji Meta Horizon na telefonie,"
    Write-Host " 3. w goglach okno 'Allow USB debugging' -> 'Always allow from this computer'."
    exit 1
}

Write-Host "Instaluję JARVIS..."
Adb install -r $Apk

Write-Host "Daję uprawnienia (mikrofon, powiadomienia, uruchamianie aplikacji z tła, bez usypiania)..."
Adb shell pm grant $pkg android.permission.RECORD_AUDIO
Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS
# these two are best effort: without them JARVIS works, but may not open apps from the background or may be put to sleep
& $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
if ($LASTEXITCODE -ne 0) { Write-Warning "Nie udało się dać zgody na uruchamianie aplikacji z tła (appops)." }
& $adb shell dumpsys deviceidle whitelist +$pkg | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Warning "Nie udało się wyłączyć usypiania (deviceidle)." }

Write-Host ""
Write-Host "Klucz API Claude (z platform.claude.com) pozwala JARVISOWI odpowiadać na dowolne pytania."
Write-Host "Bez klucza działają same komendy. Klucz trafia tylko na gogle."
$secure = Read-Host "Wklej klucz i naciśnij Enter (albo sam Enter, żeby pominąć)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $key = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Trim() }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

if ($key) {
    if ($key -notmatch '^sk-ant-[A-Za-z0-9_\-]+$') { throw "To nie wygląda na klucz API Claude (powinien zaczynać się od sk-ant-)." }
    Adb shell am start -n "$pkg/.MainActivity" --es api_key $key | Out-Null
} else {
    Adb shell am start -n "$pkg/.MainActivity" | Out-Null
}

Write-Host ""
Write-Host "Gotowe. Załóż gogle: JARVIS pobiera modele mowy (około 600 MB, przez Wi-Fi), potem powie 'JARVIS gotowy, sir.'"
Write-Host "Spróbuj: 'JARVIS, która godzina?' albo 'JARVIS, uruchom Spotify'."
