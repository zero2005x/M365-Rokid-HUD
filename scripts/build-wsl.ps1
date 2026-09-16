param([string]$Distribution = 'kali-linux')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$linuxRoot = & wsl.exe -d $Distribution --exec wslpath -a $projectRoot.Replace('\', '/')
if ($LASTEXITCODE -ne 0 -or !$linuxRoot) { throw 'Cannot resolve project directory in WSL.' }
$linuxRoot = $linuxRoot.Trim()
& wsl.exe -d $Distribution --exec bash "$linuxRoot/scripts/build-wsl.sh" "$linuxRoot"
if ($LASTEXITCODE -ne 0) { throw "WSL build failed with exit code $LASTEXITCODE" }
