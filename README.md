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

### 之前“显示已解锁、功能没效果”的原因（已修复）

格窗有两套完全独立的授权路径，之前的模块只覆盖了第一套：

| 用途 | 实现 | 旧模块是否覆盖 |
| --- | --- | --- |
| 设置页卡片显示“已解锁” | `NeXtepWindowController.workbenchState()` 返回的 Bundle，App 侧 `UnlockSettingsCard` 读 `LICENSE_UNLOCKED` / `LICENSE_TRIAL` | 覆盖（因此卡片显示已解锁） |
| **功能是否真的放行** | SystemUI 进程内 `wg.a()`：先比对模块自身签名（`wg.f()`），再解析 `panes_unlock_v1` 解锁码 | **未覆盖 → 所有付费功能仍被挡** |

`NeXtepWindowController.paidAllowed()`、`pauseTrialAllows()`、`turnsLandscape()`、
`initializeIfNeeded` 的 `paid` 标志全部走 `license.a()`，与 Bundle 无关。
所以只改 Bundle 只能改 UI，功能会继续弹“未解锁”。

### 修复方式

`MultiAppHookModule` 新增 `hookPanesLicense()`：

- 在 `com.android.systemui`（宿主）与 `io.github.yishisanren.panes` 进程内查找混淆类 `wg`；
- 类还没加载时（它在格窗自己的 ClassLoader 里），挂 `ClassLoader.loadClass` /
  `BaseDexClassLoader.findClass` 监听，真正加载到目标类时补挂；
- 结构校验通过后，把 `wg.a()Z`（功能放行）与 `wg.c()Z`（已购买）无条件返回 `true`；
- 同一进程里格窗可能加载多份 dex，按 Class 身份逐个副本挂，不再只挂第一份；
- 兜底：`NeXtepWindowController` 的 `paidAllowed` / `pauseTrialAllows` /
  `turnsLandscape` 直接返回 `true`，`WorkbenchToolsController.showUnlockPrompt`
  直接不弹（避免解锁提示挡住任务界面）；
- 保留原有 Bundle 伪造，设置页与功能两侧同时成立。

只为 `wg` 的无参布尔方法挂 hook，不触碰其它 `a()` 重载，避免误伤。

### 验证

重装 APK、重启 SystemUI 后：

```bash
adb logcat -s LSPosed  | grep PanesUnlock
# 期望看到：
# [PanesUnlock] 授权闸门已放行: wg（2 个方法，loader=...）
```

然后测试此前受限的功能（多窗格、暂停、场景、对比、横屏置于顶部），
应不再弹出解锁提示。

### 仍需注意

- 若某些功能在**模块自身**（格窗 App 进程）里还有独立校验（不读 Bundle、也不调
  `wg.a()`），需要再定位对应方法；反馈具体功能与提示即可补精确 hook。
- `injectTrialToken()` 写入 `Settings.Global` 需要 `WRITE_SECURE_SETTINGS`，
  普通应用进程通常拿不到，失败会静默跳过；现在功能闸门已被绕过，它不再是必需路径。
