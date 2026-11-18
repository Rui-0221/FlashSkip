# FlashSkip 一键测试与构建脚本
# 原因：AGP 与 Gradle Test Executor 均不兼容含中文的 Windows 用户路径，
# 故构建镜像到 C:\FlashSkipBuild，且通过目录联接将 GRADLE_USER_HOME 映射到 C:\FlashSkipGradle。
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$build = 'C:\FlashSkipBuild'
$gradleHomeLink = 'C:\FlashSkipGradle'
$realGradleHome = Join-Path $env:USERPROFILE '.gradle'
$apkOut = Join-Path $src 'FlashSkip.apk'

if (-not (Test-Path $gradleHomeLink) -and (Test-Path $realGradleHome)) {
    New-Item -ItemType Junction -Path $gradleHomeLink -Target $realGradleHome | Out-Null
}
if (Test-Path $gradleHomeLink) {
    $env:GRADLE_USER_HOME = $gradleHomeLink
}

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'C:\AndroidSDKLocal'
$gradle = 'C:\Tools\gradle-8.10.2\bin\gradle.bat'

Write-Host '[1/4] 镜像源码到 ASCII 路径...'
robocopy $src $build /MIR /XD build .gradle .idea /XF *.apk /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE" }

Write-Host '[2/4] 运行核心引擎单元测试 (:core:test)...'
& $gradle -p $build :core:test --console=plain
if ($LASTEXITCODE -ne 0) { throw "core unit tests failed: $LASTEXITCODE" }

Write-Host '[3/4] 运行 Android 单测并构建 Debug APK...'
& $gradle -p $build :app:testDebugUnitTest :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "build failed: $LASTEXITCODE" }

Write-Host '[4/4] 复制 APK...'
$apk = Join-Path $build 'app\build\outputs\apk\debug\app-debug.apk'
Copy-Item $apk $apkOut -Force
Write-Host "完成: $apkOut ($((Get-Item $apkOut).Length) bytes)"
