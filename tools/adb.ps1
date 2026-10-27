<#
  Finds adb.exe for install.ps1 and log.ps1: a real adb.exe on PATH, the Android SDK, Meta Quest
  Developer Hub, Unity, or Google's Platform Tools downloaded into tools\platform-tools.
#>

function Find-Adb {
    # only a real adb.exe on PATH: an alias or wrapper named adb has no file path and can't be run with &
    $cmd = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source -and (Test-Path $cmd.Source)) { return $cmd.Source }
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Meta Quest Developer Hub\resources\bin\adb.exe",
        "$PSScriptRoot\platform-tools\adb.exe"
    )
    $candidates += Get-ChildItem "$env:ProgramFiles\Unity\Hub\Editor\*\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }

    Write-Host "Nie znalazłem adb. Pobieram Android Platform Tools od Google..."
    $zip = Join-Path $env:TEMP "platform-tools.zip"
    Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
    Expand-Archive $zip -DestinationPath $PSScriptRoot -Force
    return "$PSScriptRoot\platform-tools\adb.exe"
}
