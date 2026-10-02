param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Programs\MCLauncher'),
    [switch]$NoShortcuts
)

$ErrorActionPreference = 'Stop'
$sourceApp = Join-Path $PSScriptRoot 'MCLauncher'
$targetApp = Join-Path $InstallRoot 'app'
$launcher = Join-Path $targetApp 'MCLauncher.exe'

if (-not (Test-Path $sourceApp -PathType Container)) {
    throw 'MCLauncher folder was not found beside this installer. Extract the complete ZIP first.'
}

New-Item -ItemType Directory -Path $targetApp -Force | Out-Null
Copy-Item -Path (Join-Path $sourceApp '*') -Destination $targetApp -Recurse -Force
Copy-Item -Path (Join-Path $PSScriptRoot 'Uninstall-MCLauncher.ps1') `
    -Destination (Join-Path $InstallRoot 'Uninstall-MCLauncher.ps1') -Force

if (-not (Test-Path $launcher -PathType Leaf)) {
    throw "Application was not found after copying: $launcher"
}

if (-not $NoShortcuts) {
    $shell = New-Object -ComObject WScript.Shell
    $desktop = [Environment]::GetFolderPath('DesktopDirectory')
    $startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'MCLauncher'
    New-Item -ItemType Directory -Path $startMenu -Force | Out-Null

    foreach ($shortcutPath in @(
            (Join-Path $desktop 'MCLauncher.lnk'),
            (Join-Path $startMenu 'MCLauncher.lnk'))) {
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $launcher
        $shortcut.WorkingDirectory = $targetApp
        $shortcut.Description = 'Launch MCLauncher'
        $shortcut.Save()
    }

    $uninstallShortcut = $shell.CreateShortcut((Join-Path $startMenu 'Uninstall MCLauncher.lnk'))
    $uninstallShortcut.TargetPath = 'powershell.exe'
    $uninstallScript = Join-Path $InstallRoot 'Uninstall-MCLauncher.ps1'
    $uninstallShortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $uninstallScript + '"'
    $uninstallShortcut.WorkingDirectory = $InstallRoot
    $uninstallShortcut.Description = 'Uninstall MCLauncher'
    $uninstallShortcut.Save()
}

Write-Output "MCLauncher installed to: $InstallRoot"
Write-Output 'Settings, wallpaper and logs remain in %USERPROFILE%\.mclauncher.'