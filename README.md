# FlashSkip（闪跳）

[![Version](https://img.shields.io/badge/Version-1.0.0-161616.svg)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026--34)-3DDC84.svg)](https://developer.android.com)
[![Network](https://img.shields.io/badge/Network-Zero%20Permission-0A7EA4.svg)](#隐私与安全设计)

**FlashSkip** 是一款基于 Android 无障碍服务（`AccessibilityService`）的事件驱动开屏广告自动跳过工具——**无需 Root、零网络权限、纯本地运行**。通过纯 Kotlin 编写的规则与状态机引擎，在极短延迟内识别并点击“跳过”按钮，同时内置多层防误触机制，防止误点全屏广告落地页或正常业务按钮。

> **v1.0.0 正式版**：新增 **三套可随时切换的界面主题（「日常简约 / 终末地 Endfield / 软萌猫咪」）**，强化终末地全屏等高线与二次元战术 HUD、搭载参考美短加白插画多姿态互动猫猫与磨砂大猫头背景；底层继承热重进（Hot Resume）有界延迟补查、未知焦点窗口安全识别、多品牌后台保活直达引导、跳过成功轻提示与可选 Shizuku（`UiAutomation`）双引擎。

---

## 核心特性（v1.0.0）

- **三套前端视觉风格 & 全功能浅/深色切换**：
  - **日常简约（Classic）**：高可读性圆润白/夜卡布局，聚焦状态总览、主开关与保活进度条；
  - **终末地（Endfield）**：全屏程序化多层有机等高线、`+240M` / `SEC-04` / `FS//08` 十字准星与右侧标尺、黑黄警示斜纹 + 实时脉冲波形 + 条形码 `[ONLINE] SYNC` 战术 HUD、`Saira` 字体与 45° 切角面板；
  - **软萌猫咪（海盐奶蓝 × 银虎斑美短加白，零粉色）**：内置行走巡逻、翻肚撒娇、正坐发呆、伸懒腰 4 种苏醒姿态与蜷睡 `Zzz` 姿态；所有必要设置就绪时每次进入首页随机切换苏醒姿态，未完全开启时自动进入午睡状态；首页下半屏采用斜角淡化大猫猫头背景搭配 ~72% 磨砂卡片，保活/设置分页亦配备专属猫元素手账底纹与猫爪就绪度条；
  - 「更多设置」首屏置顶「界面外观」开关，三套风格均支持 **跟随系统 / 浅色 / 深色**。
- **双引擎架构（无障碍为主，Shizuku 可选）**：
  - 默认仅使用系统 `AccessibilityService` 独立运行，**不依赖、不绑定任何外部软件**；
  - 可在「更多设置」按需开启 **Shizuku（`UiAutomation`）实验跳过模式**（默认关闭），两套引擎共用同一套包过滤、匹配规则、防误触与关闭证据校验，Shizuku 断连后自动回退交还控制权给系统无障碍服务。
- **热重进与异步广告捕获**：
  - 应用从后台切回前台或屏幕解锁后开启约 `3.8 s` 的有界检测窗口，结合 `TYPE_WINDOW_CONTENT_CHANGED` 与最多 6 次短时补查，稳定捕获晚 1～2 秒才挂载完成的开屏跳过按钮；
  - 支持在实时焦点、默认屏幕、窗口 ID 与根节点归属核验一致时，安全读取被系统标为未知类型（`type=-1`）的焦点窗口。
- **严格关闭确认与假成功升级**：
  - 区分 `VERIFIED_CLOSED`（确认关闭）、`STILL_PRESENT`（广告仍在）、`NAVIGATED`（同包导航跳转）与 `UNOBSERVED`（未观测），关闭前刷新原窗口候选树防止缓存掩盖；
  - 遇到广告 SDK 对 `ACTION_CLICK` 返回“假成功”时自动升级手势点击；首次手势未提交且控件位置微移时，支持在唯一身份与安全位置校验下重查原目标一次。
- **轻量跳过提示与多品牌后台保活引导**：
  - 引擎确认跳过后可弹出静音、无焦点、零悬浮窗权限的系统轻提示 `FlashSkip 已跳过广告`（支持独立开关与 `1800 ms` 防抖）；
  - 加固深度休眠（Doze）与亮屏/解锁唤醒恢复，内置荣耀、华为、小米、OPPO、vivo、三星、魅族后台自启/保护页直达入口与分步手动引导，独立保留标准电池白名单入口。

---

## 界面预览（三套主题 · 浅/深双色实机截图）

> 以下界面截图全部实拍自真机并已裁剪系统状态栏。

### 1. 日常简约（Classic）

| 浅色首页 | 深色首页 |
| :---: | :---: |
| <img src="docs/media/ui-classic-light.png" width="260" alt="日常简约浅色首页" /> | <img src="docs/media/ui-classic-dark.png" width="260" alt="日常简约深色首页" /> |

### 2. 终末地（Endfield · 全屏等高线与战术 HUD）

| 浅色控制台 | 深色控制台 |
| :---: | :---: |
| <img src="docs/media/ui-endfield-light.png" width="260" alt="终末地浅色控制台" /> | <img src="docs/media/ui-endfield-dark.png" width="260" alt="终末地深色控制台" /> |

### 3. 软萌猫咪（海盐奶蓝 × 银虎斑美短加白）

| 浅色苏醒态（斜角大猫头背景 + 随机姿态） | 深色苏醒态（星空牛仔蓝 + 随机姿态） |
| :---: | :---: |
| <img src="docs/media/ui-meow-light-awake.png" width="260" alt="软萌猫咪浅色苏醒态" /> | <img src="docs/media/ui-meow-dark-awake.png" width="260" alt="软萌猫咪深色苏醒态" /> |

| 未完全开启/暂停态（蜷睡 `Zzz` 午睡猫） | 更多设置（外观置顶 + 玩具手账底纹） |
| :---: | :---: |
| <img src="docs/media/ui-meow-light-sleep.png" width="260" alt="软萌猫咪未开启蜷睡态" /> | <img src="docs/media/ui-meow-more.png" width="260" alt="软萌猫咪更多设置页" /> |

---

## 实机跳过演示与毫秒级耗时记录

以下演示录制于荣耀真机（Android 16，**v1.0.0 正式版**，已裁剪顶部状态栏），连续展示了新版界面随机姿态切换、轻量提示 `FlashSkip 已跳过广告` 以及三种典型开屏对抗场景：**标准可点按钮**、**全屏可点击父容器陷阱（防误触落地页）**、以及 **广告 SDK 对 `ACTION_CLICK` 假成功时的自动手势升级**：

<p align="center">
  <img src="docs/media/flashskip-demo.gif" width="320" alt="FlashSkip v1.0.0 实机开屏广告自动跳过演示" />
</p>

> 高清视频文件：[docs/media/flashskip-demo.mp4](docs/media/flashskip-demo.mp4)

### 本次录屏实测耗时明细（广告按钮出现 → 广告关闭）

| 场景 | 跳过按钮出现时刻 | 广告关闭时刻 | 出现 → 关闭耗时 | 事件 → 确认总耗时 | 执行方式与说明 |
|---|---|---|---|---|---|
| **1. 标准可点跳过 (`standard`)** | `19:57:00.727` | `19:57:00.760` | **`33 ms`** | `128 ms` | `NODE_ACTION` (`attempt=1`)，节点一渲染即瞬间点击关闭 |
| **2. 全屏可点击父容器陷阱 (`fullscreen_parent`)** | `19:57:03.780` | `19:57:03.840` | **`59 ms`** | `134 ms` | 祖先尺寸锁拦截全屏广告容器，直接向小按钮中心发出安全 `GESTURE`（0 误触） |
| **3. `ACTION_CLICK` 假成功升级 (`fake_success`)** | `19:57:07.095` | `19:57:07.254` | **`159 ms`** | `199 ms` | 第 1 次 `NODE_ACTION` 被广告 SDK 假成功忽略 → 核验 `STILL_PRESENT` 后自动升级 `GESTURE` 关闭，并弹出 `FlashSkip 已跳过广告` 轻提示 |

<details>
<summary>点击展开本次录屏对应的原始实测日志（精确到毫秒）</summary>

```text
[2026-11-21 19:57:00.727] TEST_LAB[standard] skip button shown
[2026-11-21 19:57:00.760] TEST_PASS[standard] closed shownAtMs=441204264 closedAtMs=441204297 buttonVisibleFor=33ms
[2026-11-21 19:57:00.762] TARGET session=3 attempt=1 via=text shake=false method=NODE_ACTION dispatch=true eventAtMs=441204263 detectAtMs=441204286 actAtMs=441204287 matchUs=2094 inputAtMs=441204290 inputReturnAtMs=441204298 dispatchRejection=-
[2026-11-21 19:57:00.857] OUTCOME result=VERIFIED_CLOSED evidence=same_window_target_and_markers_absent session=3 attempt=1 method=NODE_ACTION matchUs=2094 detectToActMs=1 actToOutcomeMs=104 eventToConfirmedMs=128 eventToInputMs=27 inputToOutcomeMs=101

[2026-11-21 19:57:03.780] TEST_LAB[fullscreen_parent] fullscreen trap + small skip shown
[2026-11-21 19:57:03.802] TARGET session=5 attempt=2 via=text shake=false method=GESTURE dispatch=true eventAtMs=441207317 detectAtMs=441207332 actAtMs=441207333 matchUs=782 inputAtMs=441207336 inputReturnAtMs=441207339 dispatchRejection=-
[2026-11-21 19:57:03.840] TEST_PASS[fullscreen_parent_safe_gesture] closed shownAtMs=441207317 closedAtMs=441207376 buttonVisibleFor=59ms
[2026-11-21 19:57:03.853] GESTURE_OK session=5 attempt=2 callbackAtMs=441207390
[2026-11-21 19:57:03.916] OUTCOME result=VERIFIED_CLOSED evidence=same_window_target_and_markers_absent session=5 attempt=2 method=GESTURE matchUs=782 detectToActMs=1 actToOutcomeMs=118 eventToConfirmedMs=134 eventToInputMs=19 inputToOutcomeMs=115

[2026-11-21 19:57:07.095] TEST_LAB[fake_success] fake-success skip shown
[2026-11-21 19:57:07.114] TEST_LAB[fake_success] fake-success ACTION_CLICK ignored!
[2026-11-21 19:57:07.115] TARGET session=7 attempt=3 via=text shake=false method=NODE_ACTION dispatch=true eventAtMs=441210632 detectAtMs=441210646 actAtMs=441210646 matchUs=1827 inputAtMs=441210648 inputReturnAtMs=441210652 dispatchRejection=-
[2026-11-21 19:57:07.202] OUTCOME result=STILL_PRESENT evidence=target_visible session=7 attempt=3 method=NODE_ACTION matchUs=1827 detectToActMs=0 actToOutcomeMs=92 eventToConfirmedMs=- eventToInputMs=16 inputToOutcomeMs=90
[2026-11-21 19:57:07.216] TARGET session=7 attempt=4 via=text shake=false method=GESTURE dispatch=true eventAtMs=441210632 detectAtMs=441210746 actAtMs=441210746 matchUs=2992 inputAtMs=441210751 inputReturnAtMs=441210752 dispatchRejection=-
[2026-11-21 19:57:07.254] TEST_PASS[fake_success_escalated_gesture] closed shownAtMs=441210632 closedAtMs=441210791 buttonVisibleFor=159ms
[2026-11-21 19:57:07.256] GESTURE_OK session=7 attempt=4 callbackAtMs=441210793
[2026-11-21 19:57:07.295] OUTCOME result=VERIFIED_CLOSED evidence=same_window_target_and_markers_absent session=7 attempt=4 method=GESTURE matchUs=2992 detectToActMs=0 actToOutcomeMs=85 eventToConfirmedMs=199 eventToInputMs=119 inputToOutcomeMs=80
```

</details>

### 自动化测试与真机回归结果

- **JVM 单元测试**：`67 / 67` 项全部通过（`:core` 49 项 + `:app` 18 项，0 失败 / 0 跳过）。
- **内置对抗靶场（[TestSplashActivity.kt](app/src/main/java/com/flashskip/TestSplashActivity.kt)）**：
  - 原 8 项核心对抗回归（5 正向跳过 + 3 负向防误触 `neg_guide` / `neg_redpacket` / `neg_center`）在荣耀 API 36 双引擎下全数通过（`PASS = 8 / 8, FAIL = 0`）；
  - 扩展靶场覆盖 `warm_resume`（热重进）、跨窗口关闭与同包导流（4 正例 `VERIFIED_CLOSED` + 2 导流 `NAVIGATED`，6/6 通过），以及 `marker_replaced` / `marker_late` / `retry_replaced` 等反例边界。
- **真实第三方应用热重进实测**：后台等待 30 秒以上热重进 50 轮（无障碍 25 轮 + 独立 Shizuku 25 轮），49 轮成功执行跳过并回到业务页（1 轮因控件移动由安全保护拦截，0 误触）；真实复杂窗口下的跨窗口关闭确认耗时与回窗证据仍在持续优化中。

---

## 核心架构与工程特性

项目采用 **纯 Kotlin 核心引擎（`:core`）与 Android 壳层（`:app`）彻底解耦** 的分层设计：

```text
core/ (纯 Kotlin JVM 模块，零 Android 依赖，支持毫秒级快照回放单测)
 ├── Models.kt            不可变节点/窗口快照模型，避免空字符串 text 遮蔽 contentDescription
 ├── PackagePolicy.kt     系统/OEM/输入法及敏感应用默认强排除；瞬态弹窗隔离
 ├── RuleIndex.kt         按包名 O(1) 索引规则库（内置主流聚合 SDK viewId 规则）
 ├── SkipMatcher.kt       三层防误触匹配器 + 摇一摇抢跑检测 + 自绘无文字跳过节点识别
 ├── TargetEvidence.kt    原窗口身份与广告标记复查、跨窗口关闭证据校验
 ├── ForegroundEvents.kt  热重进前台窗口提示、有界延迟补查与已观察业务窗口追踪
 ├── SkipStateMachine.kt  启动窗口期/冷却/假成功升级/跨应用防污染校验/息屏解锁重布防状态机
 └── SnapshotJson.kt      脱敏 UI 节点树 JSON 导出，用于回归测试 fixture

app/ (Android 服务、实验双引擎、保活与界面层)
 ├── SkipAdService.kt              极薄接入层：事件合并、FIFO 状态队列、多窗口扫描、Doze/解锁唤醒恢复
 ├── a11y/NodeExtractor.kt         单次迭代 BFS 提取快照并 100% recycle 原生节点，标记截断与子节点异常
 ├── a11y/ActionExecutor.kt        原生点击/安全手势分发、刷新复核、移动目标有限重查与阶段日志
 ├── a11y/ForegroundAccess.kt      未知类型（type=-1）焦点窗口实时一致性核验与取根
 ├── a11y/WindowTrace.kt & SkipTrace.kt  按需启用的脱敏窗口/动作阶段诊断追踪
 ├── experimental/                 可选 Shizuku 实验引擎（ExperimentalSkip / UiAutomationRunner / SkipActionGate）
 ├── keepalive/                    PermissionHelper / KeepAliveStatus / BackgroundSettings 多品牌保活检测与直达
 ├── data/SkipLog.kt & StatsStore.kt     双文件轮转日志、本地 P50/P95 延迟统计与主题/开关持久化
 ├── ui/                           FlashTheme（三风格）、EndfieldDecorViews、MeowDecorViews、SkipSuccessToast 等
 └── MainActivity.kt & TestSplashActivity.kt  三分区用户界面、20+ 场景分组靶场与 launch_scenario 自动化入口
```

---

## 隐私与安全设计

- **硬核防误触**：
  - 精准词边界正则 + 强语义黑名单（一票否决“引导 / 教程 / 此步 / 登录 / 片头 / 领取 / 下载 / 红包 / 支付”等文案）；
  - **祖先尺寸锁**：向上寻找可点击容器时严格限制尺寸上限，遇到全屏广告父容器立即截断，绝不误点广告主体；
  - **窗口归一化区域校验**：同时在屏幕与根窗口两个参考系校验位置，防止开屏动画滑入偏移导致中心按钮误判；
  - 默认强排除系统组件、主流输入法与微信/支付宝/云闪付/主流银行等敏感应用；支持自定义排除包名。
- **极简权限与本地隐私**：
  - **不申请 `INTERNET` 权限**，无任何联网行为，`allowBackup="false"`；
  - 所有跳过统计、运行日志与脱敏诊断仅保存在本机应用私有目录，不包含包名明文或设备标识。

---

## 构建、测试与验证

### 1. 环境要求与命令行构建

- **环境要求**：JDK 17、Android SDK（`compileSdk 34` / `buildToolsVersion 36.0.0`）、Gradle 8.10.x（或直接用 Android Studio 打开根目录同步构建）。
- 使用本地 Gradle 运行全部单元测试并构建 Debug APK：

  ```bash
  gradle :core:test :app:testDebugUnitTest :app:assembleDebug
  ```

- 生成 APK 默认输出在 `app/build/outputs/apk/debug/app-debug.apk`。若 Windows 用户目录包含非 ASCII 字符导致 Gradle/AGP 路径异常，建议将工程复制到纯英文路径下构建，或附加 `-Pandroid.overridePathCheck=true`。

### 2. 在设备端进行靶场验证

安装并在系统“无障碍设置”中开启 **FlashSkip**（Android 13+ 需先在“应用信息 → 右上角菜单”允许受限设置）后，可通过以下任意方式验证对抗场景：

- **方式 A（应用内点击）**：打开 FlashSkip，进入「更多设置 → 20 项效果测试」，依次体验正向跳过、热重进、跨窗口及负向防误触对照页；
- **方式 B（`adb` 命令行拉起指定场景）**：

  ```bash
  adb shell am start --activity-clear-top --activity-single-top -n com.flashskip/.MainActivity --es launch_scenario standard
  ```

  常用场景值：`standard`、`non_clickable`、`fullscreen_parent`、`fake_success`、`desc_only`、`warm_resume`、`neg_guide`、`neg_redpacket`、`neg_center`、`redirect`、`hide_target`、`marker_replaced`、`marker_late`、`retry_replaced`。
