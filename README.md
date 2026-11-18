# FlashSkip（闪跳）

Android 开屏广告自动跳过 App。事件驱动：无障碍服务监听窗口事件 → 纯 Kotlin 规则引擎与防误触安全闸判定 → 节点点击 / 坐标手势升级执行。

- 完整规划：`../开屏广告自动跳过App-实现规划书.md`
- 当前版本：**v0.6.0-m1**（核心引擎解耦 + 防误触安全重构 + 8 项对抗靶场）
- 零权限、不联网，日志与统计仅存本机

## 模块结构

| 模块 / 文件 | 职责 |
|---|---|
| `core/` (`com.flashskip.core`) | 纯 Kotlin 核心引擎（无 Android 依赖，可直接跑 JVM 单元测试）：`SkipStateMachine`、`SkipMatcher`、`PackagePolicy`、`RuleIndex`、`SnapshotJson` |
| `app/a11y/NodeExtractor.kt` | 迭代遍历提取 `WindowSnapshot` 并保证 `AccessibilityNodeInfo` 100% 回收 |
| `app/a11y/ActionExecutor.kt` | 节点点击执行与屏幕安全边界裁剪手势执行 |
| `app/SkipAdService.kt` | 极薄无障碍服务接入层：最新事件合并队列、窗口期外零 IPC 丢弃、多窗口兜底 |
| `app/data/SkipLog.kt` & `StatsStore.kt` | 异步双文件轮转日志 + 本地 P50/P95 延迟统计 |
| `app/keepalive/PermissionHelper.kt` | `ComponentName` 精确服务检测、受限设置/电池优化与 OEM 保活引导 |
| `app/TestSplashActivity.kt` | 8 项正/负向对抗测试靶场 |
| `app/MainActivity.kt` | 仪表盘、统计展示、安全排除名单管理、日志复制导出 |

## 构建与测试

本机用户名含中文，AGP 拒绝非 ASCII 路径，故使用 `build.ps1` 镜像到 `C:\FlashSkipBuild` 执行测试与构建：

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

脚本将依次执行 `:core:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`，并输出 `FlashSkip.apk`。

## 安全机制

- 默认启动窗口期 **6s**（3–15s 可调，超时立刻休眠零扫树）；冷却 **600ms**；单次最多 **4 次**
- 精准词边界匹配 + 强语义黑名单（拦截“跳过引导/此步/片头/领红包”等）
- 几何面积校验 + **祖先尺寸锁**（禁止攀爬并点击全屏可点击广告父容器）
- 自动升级链：`ACTION_CLICK` 假成功时第 2 次自动升级为安全坐标 `dispatchGesture`
- 默认排除系统组件、主流输入法及 19 个银行/支付敏感应用
