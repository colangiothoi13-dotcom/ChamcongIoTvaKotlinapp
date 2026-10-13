param(
    [string]$ArduinoCli = 'D:/IoT/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe',
    [string]$CoreVersion = '3.3.12',
    [ValidateSet('S3N16R8', 'AiThinker')]
    [string]$BoardProfile = 'S3N16R8',
    [switch]$NativeUsbCdc,
    [switch]$InstallCore,
    [switch]$InstallTools
)

$ErrorActionPreference = 'Stop'
if ($CoreVersion -notmatch '^3\.\d+\.\d+$') {
    throw 'Choose a stable ESP32 core 3.x version.'
}
if ($NativeUsbCdc -and $BoardProfile -ne 'S3N16R8') {
    throw '-NativeUsbCdc is supported only by the ESP32-S3 profile.'
}
if (-not (Test-Path -LiteralPath $ArduinoCli -PathType Leaf)) {
    $cliCommand = Get-Command arduino-cli -ErrorAction SilentlyContinue
    if (-not $cliCommand) { throw 'Provide -ArduinoCli with the path to arduino-cli.exe.' }
    $ArduinoCli = $cliCommand.Source
}

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$buildWorkspace = $projectRoot
$temporaryDrive = $null
# GCC's Windows multilib search paths contain ../ segments before resolving
# the actual header. A long checkout path can exceed MAX_PATH even though the
# normalized file path exists. Map only this workspace for this process and
# release the unused drive letter in finally; no SDK files leave .gradle.
if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
    $usedDrives = @([IO.Directory]::GetLogicalDrives() | ForEach-Object { $_.TrimEnd('\') })
    $temporaryDrive = @('Z:', 'Y:', 'X:', 'W:', 'V:', 'U:', 'T:', 'S:', 'R:') |
        Where-Object { $_ -notin $usedDrives } | Select-Object -First 1
    if (-not $temporaryDrive) { throw 'No unused temporary drive letter is available for the Windows compiler.' }
    & "$env:SystemRoot/System32/subst.exe" $temporaryDrive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create the temporary workspace drive for the Windows compiler.' }
    $buildWorkspace = "$temporaryDrive\"
}

try {
    $sdkRoot = Join-Path $buildWorkspace '.gradle/esp32-arduino'
    $configPath = Join-Path $sdkRoot 'arduino-cli.yaml'
    if ($BoardProfile -eq 'S3N16R8') {
        $serialOption = if ($NativeUsbCdc) { 'cdc' } else { 'default' }
        $buildSuffix = if ($NativeUsbCdc) { 's3n16r8-native-cdc' } else { 's3n16r8-uart' }
        $boardLabel = 'ESP32S3 Dev Module, N16R8, 16MB flash, OPI PSRAM'
        $fqbn = 'esp32:esp32:esp32s3:CPUFreq=240,FlashMode=qio,FlashSize=16M,PSRAM=opi,' +
            "PartitionScheme=app3M_fat9M_16MB,USBMode=hwcdc,CDCOnBoot=$serialOption,UploadMode=default"
    } else {
        $buildSuffix = 'ai-thinker'
        $boardLabel = 'AI Thinker ESP32-CAM, 4MB flash, QSPI PSRAM'
        $fqbn = 'esp32:esp32:esp32cam:CPUFreq=240,FlashMode=qio,PartitionScheme=huge_app'
    }
    $stagePath = Join-Path $buildWorkspace ".gradle/esp32-camera-validation/$buildSuffix/esp32_cam"
    $buildPath = Join-Path $buildWorkspace ".gradle/esp32-camera-build-$buildSuffix"
    New-Item -ItemType Directory -Path $sdkRoot, $stagePath, $buildPath -Force | Out-Null
    $yamlRoot = $sdkRoot.Replace('\', '/').Replace("'", "''")
    $configuration = @"
board_manager:
  additional_urls:
    - https://espressif.github.io/arduino-esp32/package_esp32_index.json
directories:
  data: '$yamlRoot/data'
  downloads: '$yamlRoot/downloads'
  user: '$yamlRoot/user'
build_cache:
  path: '$yamlRoot/build-cache'
network:
  connection_timeout: 120s
"@
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($configPath, $configuration, $utf8)

    $installedPlatform = Join-Path $sdkRoot "data/packages/esp32/hardware/esp32/$CoreVersion/platform.txt"
    $indexPath = Join-Path $sdkRoot 'data/package_esp32_index.json'
    $officialIndexPath = Join-Path $sdkRoot 'data/package_esp32_index.official.json'
    # Keep the classic toolchain available when adding S3 libraries. Both chips
    # use esp-x32; only the target-specific precompiled libraries differ.
    $requiredTools = @('esp-x32', 'esp32-libs', 'esptool_py', 'mklittlefs', 'mkspiffs')
    if ($BoardProfile -eq 'S3N16R8') { $requiredTools += 'esp32s3-libs' }
    $missingTools = @($requiredTools)
    if (Test-Path -LiteralPath $officialIndexPath -PathType Leaf) {
        $index = Get-Content -LiteralPath $officialIndexPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $package = $index.packages | Where-Object name -eq 'esp32'
        $platform = $package.platforms | Where-Object version -eq $CoreVersion
        if ($platform) {
            $missingTools = @($requiredTools | Where-Object {
                $toolName = $_
                $dependency = $platform.toolsDependencies | Where-Object {
                    $_.packager -eq 'esp32' -and $_.name -eq $toolName
                }
                -not $dependency -or -not (Test-Path -LiteralPath (
                    Join-Path $sdkRoot "data/packages/esp32/tools/$toolName/$($dependency.version)"
                ) -PathType Container)
            })
        }
    }
    if (-not (Test-Path -LiteralPath $installedPlatform -PathType Leaf) -or $missingTools.Count -gt 0) {
        if (-not ($InstallCore -or $InstallTools)) {
            throw "ESP32 core $CoreVersion or $BoardProfile dependencies are missing here. Run with -InstallCore or -InstallTools."
        }
        & $ArduinoCli --config-file $configPath core update-index
        if ($LASTEXITCODE -ne 0) { throw 'Cannot download the official package indexes.' }
        # Keep official archives, versions and SHA-256 checksums. Skip unrelated
        # RISC-V compilers, debuggers and other chip libraries. This SDK is private
        # to this checkout; the Arduino IDE's global installation is unchanged.
        Copy-Item -LiteralPath $indexPath -Destination $officialIndexPath
        $index = Get-Content -LiteralPath $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $package = $index.packages | Where-Object name -eq 'esp32'
        $platform = $package.platforms | Where-Object version -eq $CoreVersion
        if (-not $platform) { throw "ESP32 $CoreVersion is missing from the official stable index." }
        foreach ($toolName in $requiredTools) {
            if (-not ($platform.toolsDependencies | Where-Object {
                $_.packager -eq 'esp32' -and $_.name -eq $toolName
            })) {
                throw "ESP32 $CoreVersion does not use the supported dependency layout: missing $toolName."
            }
        }
        $platform.toolsDependencies = @($platform.toolsDependencies | Where-Object {
            $_.packager -eq 'esp32' -and $_.name -in $requiredTools
        })
        [System.IO.File]::WriteAllText($indexPath, ($index | ConvertTo-Json -Depth 100), $utf8)
        & $ArduinoCli --config-file $configPath core install "esp32:esp32@$CoreVersion" --skip-post-install
        if ($LASTEXITCODE -ne 0) { throw 'ESP32 core installation failed.' }
        foreach ($dependency in $platform.toolsDependencies) {
            $toolPath = Join-Path $sdkRoot "data/packages/esp32/tools/$($dependency.name)/$($dependency.version)"
            if (-not (Test-Path -LiteralPath $toolPath -PathType Container)) {
                # Arduino CLI skips dependency installation if this platform
                # version already exists (for example, a classic-only SDK).
                # Repair a missing Windows ZIP tool directly, using the exact
                # URL/size/SHA-256 from the official index, without removing any
                # installed platform or touching the user's global SDK.
                if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
                    throw 'Repairing an existing selective SDK currently requires Windows.'
                }
                $tool = $package.tools | Where-Object {
                    $_.name -eq $dependency.name -and $_.version -eq $dependency.version
                }
                $hostName = if ([Environment]::Is64BitOperatingSystem) { 'x86_64-mingw32' } else { 'i686-mingw32' }
                $archive = $tool.systems | Where-Object host -eq $hostName | Select-Object -First 1
                if (-not $archive) {
                    $archive = $tool.systems | Where-Object host -eq 'i686-mingw32' | Select-Object -First 1
                }
                if (-not $archive -or $archive.archiveFileName -notmatch '^[^\\/]+\.zip$' -or
                    $archive.checksum -notmatch '^SHA-256:([0-9a-fA-F]{64})$' -or
                    $archive.url -notmatch '^https://') {
                    throw "Cannot repair $($dependency.name): unsupported official archive metadata."
                }
                $expectedHash = $archive.checksum.Substring('SHA-256:'.Length)
                $archivePath = Join-Path $sdkRoot "downloads/packages/$($archive.archiveFileName)"
                New-Item -ItemType Directory -Path (Split-Path $archivePath) -Force | Out-Null
                $archiveValid = (Test-Path -LiteralPath $archivePath -PathType Leaf) -and
                    (Get-Item -LiteralPath $archivePath).Length -eq [long]$archive.size -and
                    (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -eq $expectedHash
                if (-not $archiveValid) {
                    Write-Output "Download missing SDK tool: $($dependency.name) $($dependency.version)."
                    $savedProgressPreference = $ProgressPreference
                    try {
                        $ProgressPreference = 'SilentlyContinue'
                        Invoke-WebRequest -UseBasicParsing -Uri $archive.url -OutFile $archivePath -TimeoutSec 240
                    } finally {
                        $ProgressPreference = $savedProgressPreference
                    }
                }
                if ((Get-Item -LiteralPath $archivePath).Length -ne [long]$archive.size -or
                    (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $expectedHash) {
                    throw "Official archive size/SHA-256 validation failed for $($dependency.name)."
                }
                $extractPath = Join-Path $sdkRoot ('install-cache/' + [Guid]::NewGuid().ToString('N'))
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                [IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $extractPath)
                $archiveRoots = @(Get-ChildItem -LiteralPath $extractPath -Force)
                if ($archiveRoots.Count -ne 1 -or -not $archiveRoots[0].PSIsContainer) {
                    throw "Unexpected archive root layout for $($dependency.name)."
                }
                $sdkAbsolute = [IO.Path]::GetFullPath($sdkRoot).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
                $sourceAbsolute = [IO.Path]::GetFullPath($archiveRoots[0].FullName)
                $targetAbsolute = [IO.Path]::GetFullPath($toolPath)
                if (-not $sourceAbsolute.StartsWith($sdkAbsolute, [StringComparison]::OrdinalIgnoreCase) -or
                    -not $targetAbsolute.StartsWith($sdkAbsolute, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'SDK repair paths must remain inside this workspace SDK.'
                }
                New-Item -ItemType Directory -Path (Split-Path $toolPath) -Force | Out-Null
                Move-Item -LiteralPath $sourceAbsolute -Destination $targetAbsolute
                Remove-Item -LiteralPath $extractPath
                Write-Output "Installed verified SDK tool: $($dependency.name) $($dependency.version)."
            }
        }
    }

    # Always compile a staging copy with non-secret test configuration. Never read
    # a real secrets.h or generate a binary intended for immediate device upload.
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'esp32_cam.ino') -Destination (Join-Path $stagePath 'esp32_cam.ino')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'camera_board.h') -Destination (Join-Path $stagePath 'camera_board.h')
    $testSecrets = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'secrets.h.example') -Raw -Encoding UTF8).Replace(
        'REPLACE_WITH_RANDOM_CAMERA_TOKEN', 'compile-only-camera-token-placeholder'
    )
    [System.IO.File]::WriteAllText((Join-Path $stagePath 'secrets.h'), $testSecrets, $utf8)
    Write-Output "Compile only: ESP32 $CoreVersion, $boardLabel; placeholder Wi-Fi/token."
    Write-Output "Board options: $fqbn"
    # camera_board.h derives its profile from the selected ESP-IDF chip target,
    # exactly as in Arduino IDE. Preserve Espressif's PSRAM and USB/CDC flags.
    & $ArduinoCli --config-file $configPath compile --fqbn $fqbn `
        --build-path $buildPath $stagePath
    if ($LASTEXITCODE -ne 0) { throw 'ESP32-CAM compilation failed.' }
    Write-Output ('Build complete: ' + (Join-Path $projectRoot ".gradle/esp32-camera-build-$buildSuffix"))
} finally {
    if ($temporaryDrive) {
        & "$env:SystemRoot/System32/subst.exe" $temporaryDrive /D
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not release temporary workspace drive $temporaryDrive" }
    }
}
