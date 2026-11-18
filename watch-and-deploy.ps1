# 双通道自动部署监控：adb 安装 或 WPD 文件拷贝
# 窗口 30 分钟，每 10 秒探测一次
$adb = 'C:\AndroidSDKLocal\platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'FlashSkip.apk'
if (-not (Test-Path $apk)) {
    throw "APK not found: $apk. Run build.ps1 first."
}
$expect = (Get-Item $apk).Length
$done = $false
$adbFailed = $false

for ($i = 0; $i -lt 180; $i++) {
    Start-Sleep -Seconds 10

    # 通道 A：adb（需 USB 调试+授权）
    if (-not $adbFailed) {
        $auth = & $adb devices 2>$null | Select-String -Pattern '^(\S+)\s+device$'
        if ($auth) {
            $serial = $auth.Matches[0].Groups[1].Value
            "ADB DEVICE APPEARED: $serial (~$($i*10)s)"
            & $adb -s $serial shell getprop ro.product.brand
            & $adb -s $serial shell getprop ro.product.model
            & $adb -s $serial shell getprop ro.build.version.release
            $out = & $adb -s $serial install -r $apk 2>&1 | Out-String
            $out
            if ($out -match 'Success') { "RESULT: INSTALL VIA ADB OK"; $done = $true; break }
            else { "RESULT: adb install failed (可能需手机点允许/开启USB安装权限)，转 WPD 通道"; $adbFailed = $true }
        }
    }

    # 通道 B：WPD 文件拷贝（需解锁屏幕+传输文件模式）
    try {
        $shell = New-Object -ComObject Shell.Application
        $dev = $shell.Namespace(17).Items() | Where-Object { $_.Path -match 'vid_339b' }
        if ($dev) {
            $ns = $shell.Namespace($dev.Path)
            if ($ns -and $ns.Items().Count -gt 0) {
                "WPD STORAGE ACCESSIBLE (~$($i*10)s)"
                $storage = $ns.Items() | Where-Object { $_.Name -match '内部存储|Internal' } | Select-Object -First 1
                if (-not $storage) { $storage = $ns.Items() | Where-Object { $_.IsFolder } | Select-Object -First 1 }
                if ($storage) {
                    $dl = $storage.GetFolder.Items() | Where-Object { $_.Name -eq 'Download' } | Select-Object -First 1
                    if ($dl) {
                        $dl.GetFolder.CopyHere($apk, 0x14)
                        for ($j = 0; $j -lt 30; $j++) {
                            Start-Sleep -Seconds 2
                            $f = $dl.GetFolder.Items() | Where-Object { $_.Name -eq 'FlashSkip.apk' }
                            if ($f -and $f.Size -eq $expect) { "RESULT: COPY TO PHONE Download OK"; $done = $true; break }
                        }
                        if ($done) { break }
                    } else { "WPD: Download folder not found" }
                }
            }
        }
    } catch { "WPD probe error: $($_.Exception.Message)" }
}
if (-not $done) { "RESULT: TIMEOUT 30min - 两条通道都未就绪（需解锁屏幕或开USB调试）" }
