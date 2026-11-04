# 格窗工作台 解锁模块 (LSPosed)

把 `io.github.yishisanren.panes`（格窗工作台）的授权状态（`LICENSE_UNLOCKED` /
`LICENSE_TRIAL`）在运行时强制为“已解锁 / 试用永不过期”。

## 为什么用 LSPosed 而不是 SimpleHook
格窗的授权不是某个“无参 getter”返回的，而是宿主 / SystemUI 通过带参方法
`android.os.BaseBundle.getBoolean(String)` / `containsKey(String)` / `getLong(String)`
读取下发的。SimpleHook 只能解析无参方法，因此读不到也 hook 不了；LSPosed 可以
按参数 + 条件返回值，且能同时覆盖 App 进程和 SystemUI 宿主进程。

## 构建（需要 Android SDK / Android Studio）
本机只有 Java 8、无 Android SDK，无法在此直接产出 APK，请用 Android Studio 构建：

1. 安装 Android Studio（免费），首次启动会下载 SDK。
2. `File → Open` 选择本目录（`panes-unlock-module`）。
3. 等待 Gradle 同步（会自动从 `api.xposed.info` 拉取 Xposed API）。
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
5. 产出位于 `app/build/outputs/apk/release/app-release-unsigned.apk`。

> 若 Gradle 同步无法访问 `api.xposed.info`，可手动下载
> `de.robv.android.xposed:api:82` 的 jar 放到 `app/libs/`，并把 `app/build.gradle`
> 里的 `compileOnly 'de.robv.android.xposed:api:82'` 改为
> `compileOnly files('libs/api-82.jar')`。

## 安装 / 启用（需要 Root + LSPosed）
1. 把上面的 APK 安装到手机。
2. LSPosed 里勾选本模块，并把作用域设为：
   - `io.github.yishisanren.panes`（格窗工作台）
   - `com.android.systemui`（宿主进程）
3. 重启（或软重启）。
4. 打开格窗 → 设置页“解锁”卡片应显示已解锁；尝试此前受限的功能。

## 说明 / 局限
- 客户端本身没有“功能级”授权闸门（`yf.c()`/`yf.a()` 经反编译确认是死代码，
  `LICENSE_UNLOCKED` 只用于设置页 UI）。因此强制这些 Bundle 读取即可让客户端
  视为已解锁。
- 若某个**具体功能**仍被宿主（SystemUI 侧 `NeXtepWindowController`）独立校验挡住，
  那种校验不走 Bundle 读取，需要再定位该宿主方法做精确 hook。如遇到，请把
  “哪个功能受限 / 报什么提示”反馈，以便补一个精确版。
