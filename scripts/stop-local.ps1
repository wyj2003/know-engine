param([switch]$AppOnly, [switch]$MineruOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = $env:KNOW_ENGINE_SERVICES_DIR
if ([string]::IsNullOrWhiteSpace($serviceRoot)) { $serviceRoot = 'D:\develop\know-engine-services' }
if ($AppOnly -and $MineruOnly) { throw 'Choose AppOnly or MineruOnly.' }
$ports = if ($AppOnly) { @(8009) } elseif ($MineruOnly) { @(8000) } else { @(8009, 8000, 9200, 9090) }

function Stop-OwnedProcessTree([int]$RootProcessId) {
    # Snapshot before stopping the verified root: MinerU rendering workers otherwise become orphaned.
    $snapshot = @(Get-CimInstance Win32_Process)
    $processIds = [Collections.Generic.List[int]]::new()
    $processIds.Add($RootProcessId)
    for ($index = 0; $index -lt $processIds.Count; $index++) {
        foreach ($child in $snapshot) {
            if ($child.ParentProcessId -eq $processIds[$index] -and -not $processIds.Contains([int]$child.ProcessId)) {
                $processIds.Add([int]$child.ProcessId)
            }
        }
    }
    for ($index = $processIds.Count - 1; $index -ge 0; $index--) {
        Stop-Process -Id $processIds[$index] -ErrorAction SilentlyContinue
    }
}
foreach ($port in $ports) {
    $owners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($owner in $owners) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $owner"
        $commandLine = [string]$processInfo.CommandLine
        $isProjectProcess = $commandLine.Contains($projectRoot) -or $commandLine.Contains($projectRoot.Replace('\', '/'))
        $isLocalService = $commandLine.Contains($serviceRoot) -or $commandLine.Contains($serviceRoot.Replace('\', '/'))
        if ($isProjectProcess -or $isLocalService) {
            Stop-OwnedProcessTree $owner
            Write-Host "Stopped project process on port $port."
        } else {
            Write-Host "Port $port belongs to another process; left running."
        }
    }
}
