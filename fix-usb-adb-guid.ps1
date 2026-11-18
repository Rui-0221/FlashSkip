# ============================================================
# FlashSkip USB ADB 修复脚本（方法二：注册表 GUID，最轻可逆）
# ------------------------------------------------------------
# 根因：Windows 给手机 ADB 接口装了通用 WinUSB 驱动，注册的接口
#       GUID 是 {8a4d20a8-...}，而 adb 只扫描安卓标准 GUID
#       {F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}，两者不匹配。
# 做法：给该接口的 Device Parameters 追加 DeviceInterfaceGUIDs，
#       让现有 WinUSB 驱动额外注册 adb 认的 GUID，再重启设备节点。
# 特点：不替换任何驱动、不关驱动签名、只影响这一个设备实例。
# 撤销：删除下面写入的 DeviceInterfaceGUIDs 值，再重启设备节点即可。
#
# 用法：手机开 USB 调试并插好 -> 右键本脚本 -> 使用 PowerShell 以管理员身份运行
# ============================================================

$ErrorActionPreference = 'Stop'
$AdbGuid = '{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}'
$adbExe  = 'C:\AndroidSDKLocal\platform-tools\adb.exe'

# --- 0. 必须管理员 ---
$id = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object System.Security.Principal.WindowsPrincipal($id)
if (-not $pr.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host '[错误] 请右键本脚本 -> 使用 PowerShell 以管理员身份运行。' -ForegroundColor Red
    Read-Host '按回车退出'
    exit 1
}

# --- 1. 找荣耀手机的 ADB 接口（VID_339B 且兼容 ID 含 SubClass_42） ---
$cands = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -like 'USB\VID_339B*' }
$target = $null
foreach ($d in $cands) {
    $compat = ($d | Get-PnpDeviceProperty -KeyName 'DEVPKEY_Device_CompatibleIds' -ErrorAction SilentlyContinue).Data
    if (($compat -join ' ') -match 'SubClass_42') { $target = $d; break }
}
if (-not $target) {
    foreach ($d in $cands) { if ($d.FriendlyName -match 'ADB') { $target = $d; break } }
}
if (-not $target) {
    Write-Host '[错误] 没找到荣耀手机的 ADB 接口。请确认：' -ForegroundColor Red
    Write-Host '  1) 手机已用 USB 插上电脑；'
    Write-Host '  2) 已开 USB 调试并点了"始终允许"授权；'
    Write-Host '  3) 设备管理器里能看到 "ADB Interface"。'
    Read-Host '按回车退出'
    exit 1
}
Write-Host "[找到] $($target.FriendlyName)  $($target.InstanceId)" -ForegroundColor Green

# --- 2. 追加 DeviceInterfaceGUIDs（保留已有 GUID，不覆盖） ---
$regPath = "HKLM:\SYSTEM\CurrentControlSet\Enum\$($target.InstanceId)\Device Parameters"
if (-not (Test-Path $regPath)) { New-Item -Path $regPath -Force | Out-Null }
$existing = @()
try { $existing = @((Get-ItemProperty -Path $regPath -Name DeviceInterfaceGUIDs -ErrorAction Stop).DeviceInterfaceGUIDs) } catch {}
if ($existing -notcontains $AdbGuid) {
    $new = @($existing) + $AdbGuid
    New-ItemProperty -Path $regPath -Name DeviceInterfaceGUIDs -Value $new -PropertyType MultiString -Force | Out-Null
    Write-Host "[写入] DeviceInterfaceGUIDs = $($new -join ', ')" -ForegroundColor Green
} else {
    Write-Host '[跳过] 已包含目标 GUID，无需重复写入。' -ForegroundColor Yellow
}

# --- 3. 重启设备节点，让 WinUSB 重新注册接口 ---
Write-Host '[重启] 禁用再启用该设备节点...' -ForegroundColor Cyan
try {
    Disable-PnpDevice -InstanceId $target.InstanceId -Confirm:$false -ErrorAction Stop
    Start-Sleep -Seconds 2
    Enable-PnpDevice -InstanceId $target.InstanceId -Confirm:$false -ErrorAction Stop
    Start-Sleep -Seconds 3
    Write-Host '[完成] 设备节点已重启。' -ForegroundColor Green
} catch {
    Write-Host "[提示] 自动重启失败：$($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host '       请手动把手机 USB 拔下再插上（效果相同）。'
}

# --- 4. 重启 adb 并验证 ---
if (Test-Path $adbExe) {
    & $adbExe kill-server 2>$null | Out-Null
    Start-Sleep -Seconds 1
    & $adbExe start-server 2>$null | Out-Null
    Start-Sleep -Seconds 3
    Write-Host '=== adb devices ===' -ForegroundColor Cyan
    & $adbExe devices -l
} else {
    Write-Host "[提示] 未找到 adb：$adbExe，请自行运行 adb devices 验证。" -ForegroundColor Yellow
}

Write-Host ''
Write-Host '若上面出现 "xxxx device"（而非 unauthorized/空），即修复成功。' -ForegroundColor Green
Write-Host '若仍为空：把手机 USB 拔插一次再看；或确认手机授权弹窗已点允许。'
Write-Host '撤销方法：删除注册表该接口的 DeviceInterfaceGUIDs 值后重启设备节点。'
Read-Host '按回车退出'
