# FlashSkip 真机自动验证脚本（USB adb）
# 前提：手机已开 USB 调试并授权、APK 已安装（或本脚本自动安装）
# 用法：powershell -ExecutionPolicy Bypass -File verify-on-device.ps1

$ErrorActionPreference = 'Continue'
$adb = 'C:\AndroidSDKLocal\platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'FlashSkip.apk'
$pkg = 'com.flashskip'
$svc = "$pkg/$pkg.SkipAdService"
$outLog = Join-Path $PSScriptRoot '真机测试日志.txt'

function Adb { param([string[]]$Args2) & $adb @Args2 2>&1 | Out-String }

# 1. 设备
$dev = (& $adb devices | Select-String -Pattern '^(\S+)\s+device$')
if (-not $dev) { "ERROR: 无已授权设备"; exit 1 }
$serial = $dev.Matches[0].Groups[1].Value
"设备: $serial"
"品牌: $(Adb @('shell','getprop','ro.product.brand'))"
"型号: $(Adb @('shell','getprop','ro.product.model'))"
"Android: $(Adb @('shell','getprop','ro.build.version.release'))"

# 2. 安装
if (Test-Path $apk) {
    "安装/更新 APK..."
    Adb @('install','-r',$apk)
}

# 3. 解除 Android 13+ 受限设置、加电池白名单，并安全追加启用无障碍服务（保留用户原有服务）
Adb @('shell','appops','set',$pkg,'ACCESS_RESTRICTED_SETTINGS','allow') | Out-Null
Adb @('shell','dumpsys','deviceidle','whitelist',"+$pkg") | Out-Null
$existingRaw = (Adb @('shell','settings','get','secure','enabled_accessibility_services')).Trim()
$existingList = @()
if ($existingRaw -and $existingRaw -ne 'null') {
    $existingList = $existingRaw -split ':' | Where-Object { $_ -and $_.Trim() -ne '' }
}
if ($existingList -notcontains $svc) {
    $merged = ($existingList + $svc) -join ':'
    Adb @('shell','settings','put','secure','enabled_accessibility_services',$merged) | Out-Null
}
Adb @('shell','settings','put','secure','accessibility_enabled','1') | Out-Null
Start-Sleep -Seconds 3
$bound = Adb @('shell','dumpsys','accessibility')
if ($bound -match [regex]::Escape($svc)) {
    "无障碍服务: 已绑定"
} else {
    "WARNING: 服务未绑定，需检查系统受限设置/安全策略"
}

# 4. 启动主界面
Adb @('shell','am','start','-n',"$pkg/.MainActivity") | Out-Null
Start-Sleep -Seconds 2

# 5. 正向 + 负向对照靶场验证（通过 MainActivity 转发，并用日志证据双重校验）
$scenarios = @(
    @{ Name = 'standard'; ExpectSkip = $true },
    @{ Name = 'non_clickable'; ExpectSkip = $true },
    @{ Name = 'fullscreen_parent'; ExpectSkip = $true },
    @{ Name = 'fake_success'; ExpectSkip = $true },
    @{ Name = 'desc_only'; ExpectSkip = $true },
    @{ Name = 'neg_guide'; ExpectSkip = $false },
    @{ Name = 'neg_redpacket'; ExpectSkip = $false },
    @{ Name = 'neg_center'; ExpectSkip = $false }
)

$passed = 0
$failed = 0
foreach ($sc in $scenarios) {
    $name = $sc.Name
    $startMarker = (Adb @('shell','date','+%Y-%m-%d\ %H:%M:%S')).Trim()
    Adb @('shell','am','start','-n',"$pkg/.MainActivity",'--es','launch_scenario',$name) | Out-Null
    Start-Sleep -Seconds 4
    $focus = Adb @('shell','dumpsys','window')
    $stillOnSplash = ($focus -match 'mCurrentFocus.*TestSplash')
    $allLines = (Adb @('shell','run-as',$pkg,'cat','files/skip.log')) -split "`r?`n"
    $newLines = $allLines | Where-Object {
        if ($_ -match '^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})') {
            $Matches[1] -ge $startMarker
        } else { $false }
    }
    $logNew = $newLines -join "`n"
    $hasFail = ($logNew -match "TEST_FAIL\[$([regex]::Escape($name))\]")
    $hasShown = ($logNew -match "TEST_LAB\[$([regex]::Escape($name))\]")

    if ($sc.ExpectSkip) {
        if ($hasShown -and (-not $stillOnSplash) -and (-not $hasFail)) {
            "PASS [$name]: 已启动靶场并自动跳过关闭"
            $passed++
        } else {
            "FAIL [$name]: shown=$hasShown stillOnSplash=$stillOnSplash hasFail=$hasFail"
            $failed++
            if ($stillOnSplash) {
                Adb @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
                Start-Sleep -Seconds 1
            }
        }
    } else {
        if ($hasShown -and $stillOnSplash -and (-not $hasFail)) {
            "PASS [$name]: 负向对照零误触"
            $passed++
            Adb @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
            Start-Sleep -Seconds 1
        } else {
            "FAIL [$name]: shown=$hasShown stillOnSplash=$stillOnSplash hasFail=$hasFail"
            $failed++
        }
    }
}

"===== 汇总: PASS=$passed / $($scenarios.Count), FAIL=$failed ====="

# 6. 拉取服务日志
Start-Sleep -Seconds 1
$log = Adb @('shell','run-as',$pkg,'cat','files/skip.log')
Set-Content -Path $outLog -Value $log -Encoding UTF8
"日志已保存: $outLog"
"===== 日志尾部 ====="
($log -split "`n") | Select-Object -Last 30 | ForEach-Object { $_ }
