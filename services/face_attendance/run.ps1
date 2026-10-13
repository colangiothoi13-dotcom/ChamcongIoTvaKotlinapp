param(
    [Parameter(Position = 0)]
    [string]$Command = 'demo',
    [string]$Config,
    [Parameter(ValueFromRemainingArguments = $true)]
    [object[]]$CommandOptions
)

$ErrorActionPreference = 'Stop'
$taskServiceDir = $PSScriptRoot
$taskWorkspaceDir = [System.IO.Path]::GetFullPath((Join-Path $taskServiceDir '../..'))
$taskPythonCandidates = @(
    (Join-Path $taskServiceDir '.venv/Scripts/python.exe'),
    (Join-Path $taskWorkspaceDir '.gradle/face-attendance-python/python.exe')
)
$taskPython = $taskPythonCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $taskPython) {
    $taskPythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($taskPythonCommand) { $taskPython = $taskPythonCommand.Source }
}
if (-not $taskPython) {
    throw 'Can Python 3.12. Xem README.md de cai Python va moi truong .venv.'
}

# Add the package path explicitly: portable Python uses an isolated _pth file.
$taskLauncher = "import runpy,sys; sys.path.insert(0,sys.argv.pop(1)); sys.argv[0]='face_attendance'; runpy.run_module('face_attendance',run_name='__main__')"
$taskArguments = @($Command)
# Windows PowerShell 5.1 groups a remaining array argument as one object.
# Flatten it before invoking Python, preserving each photo path separately.
foreach ($taskOption in $CommandOptions) {
    foreach ($taskValue in @($taskOption)) {
        $taskArguments += [string]$taskValue
    }
}
if ($Config) {
    # Resolve before changing directory so paths passed by the caller keep their meaning.
    $taskConfigPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Config)
    $taskArguments = @('--config', $taskConfigPath) + $taskArguments
}
Push-Location -LiteralPath $taskServiceDir
try {
    & $taskPython -X utf8 -c $taskLauncher $taskServiceDir @taskArguments
    $taskExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $taskExitCode
