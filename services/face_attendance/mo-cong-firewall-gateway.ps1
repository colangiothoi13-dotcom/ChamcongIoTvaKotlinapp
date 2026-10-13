$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$python = Join-Path $workspace '.gradle/face-attendance-python/python.exe'
$ruleName = 'Chamcong IoT fingerprint gateway (LocalSubnet)'

if (-not (Test-Path -LiteralPath $python)) { throw 'Thiếu Python runtime của dịch vụ laptop.' }
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
    $valid = $false
    foreach ($rule in $existing) {
        $app = Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $rule
        $port = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule
        $address = Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $rule
        if ($rule.Enabled -eq 'True' -and $rule.Direction -eq 'Inbound' -and $rule.Action -eq 'Allow' -and
            $app.Program -eq $python -and $port.Protocol -eq 'TCP' -and $port.LocalPort -eq '8765' -and
            $address.RemoteAddress -contains 'LocalSubnet') { $valid = $true }
    }
    if (-not $valid) { throw 'Đã có rule cùng tên nhưng phạm vi khác. Nhờ quản trị viên kiểm tra trước khi sửa.' }
    Write-Host 'Rule mạng nội bộ cho Gateway đã có sẵn.'
    exit 0
}

New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Program $python `
    -Protocol TCP -LocalPort 8765 -RemoteAddress LocalSubnet -Profile Public -EdgeTraversalPolicy Block | Out-Null
Write-Host 'Đã cho phép ESP8266 trong mạng nội bộ kết nối TCP 8765 tới dịch vụ Python.'
