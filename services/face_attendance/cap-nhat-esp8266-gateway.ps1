param(
    [Parameter(Position = 0)]
    [string]$Port = 'COM3'
)

$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cli = 'D:/IoT/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe'
$sketch = Join-Path $workspace 'firmware/esp8266_fingerprint'
$secrets = Join-Path $sketch 'secrets.h'
$configPath = Join-Path $PSScriptRoot 'config.json'
$buildPath = Join-Path $workspace '.gradle/face-door-gateway-firmware-build-real'

if (-not (Test-Path -LiteralPath $cli)) { throw 'Không tìm thấy Arduino CLI trên máy này.' }
if (-not (Test-Path -LiteralPath $secrets)) { throw 'Thiếu secrets.h của ESP8266; không nạp firmware mẫu.' }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$lines = [System.IO.File]::ReadAllLines($secrets)
function Get-SecretMacro([string]$name) {
    $match = [regex]::Match(($lines -join "`n"), '(?m)^\s*#define\s+' + [regex]::Escape($name) + '\s+"([^"\r\n]*)"\s*$')
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value
}

$mode = [regex]::Match(($lines -join "`n"), '(?m)^\s*#define\s+ATTENDANCE_GATEWAY_MODE\s+(\d+)\s*$')
if (-not $mode.Success -or $mode.Groups[1].Value -ne '1' -or
    (Get-SecretMacro 'ATTENDANCE_GATEWAY_URL') -ne "http://$($config.host):$($config.port)/v1/fingerprint" -or
    (Get-SecretMacro 'ATTENDANCE_DEVICE_TOKEN') -ne $config.deviceToken -or
    (Get-SecretMacro 'ATTENDANCE_STATION_ID') -ne $config.stationId) {
    throw 'Cấu hình Gateway trong secrets.h không khớp station/config laptop; chưa nạp gì cả.'
}

Write-Host 'Đang biên dịch firmware Gateway cho ESP8266 NodeMCU; LittleFS sẽ được giữ nguyên.'
& $cli compile --fqbn 'esp8266:esp8266:nodemcuv2:eesz=4M2M,wipe=none' --build-path $buildPath $sketch
if ($LASTEXITCODE -ne 0) { throw 'Biên dịch firmware thất bại; board chưa được thay đổi.' }

Write-Host "Đang nạp qua $Port. Đóng Serial Monitor/Plotter trước nếu cổng đang bận."
$uploadOutput = & $cli upload --fqbn 'esp8266:esp8266:nodemcuv2:eesz=4M2M,wipe=none' --port $Port --input-dir $buildPath $sketch 2>&1
$uploadExit = $LASTEXITCODE
$uploadOutput | ForEach-Object { Write-Host $_ }
if ($uploadExit -ne 0 -or ($uploadOutput -join "`n") -notmatch 'Hash of data verified\.') {
    throw 'Chưa xác nhận được firmware đã ghi thành công. Kiểm tra cổng USB/Serial Monitor và thử lại.'
}
Write-Host 'Firmware Gateway đã được ghi và xác minh hash. Giữ board bật để kiểm tra heartbeat firmware mới.'
