param([string]$InstallRoot = $PSScriptRoot)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($InstallRoot)
$programs = [Environment]::GetFolderPath('Programs')
$startMenu = Join-Path $programs 'MCLauncher'
$desktopShortcut = Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) 'MCLauncher.lnk'

Remove-Item -LiteralPath $desktopShortcut -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $startMenu -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path $root -PathType Container) {
    Remove-Item -LiteralPath $root -Recurse -Force
}

Write-Output 'MCLauncher was removed. Settings, wallpaper and logs in %USERPROFILE%\.mclauncher were preserved.'