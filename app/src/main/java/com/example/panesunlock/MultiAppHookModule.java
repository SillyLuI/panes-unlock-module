package com.example.panesunlock;

import android.content.Context;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 多应用解锁模块入口（LSPosed 通过 assets/xposed_init 找到本类）。
 *
 * 机制：
 *  1) 读取 assets/hooks.json（失败回退 DEFAULT_JSON），按包名匹配目标应用；
 *  2) 格窗（io.github.yishisanren.panes）走 bundle_forge：强制 android.os.BaseBundle
 *     的 LICENSE_UNLOCKED / LICENSE_TRIAL 读取（覆盖 App 与 SystemUI 宿主），并注入永久试用令牌；
 *  3) 其它应用按 JSON 里的 rules 做 simplehook 式“改返回值”等通用 hook。
 *
 * 新增/修改应用只改 hooks.json，无需改代码（重编即可）。
 */
public class MultiAppHookModule implements IXposedHookLoadPackage {

    private static final String PANES = "io.github.yishisanren.panes";
    private static final String SYSTEMUI = "com.android.systemui";
    /** 本模块自身包名；方案 B 用于在格窗进程里隐藏它，绕过篡改检测。 */
    private static final String MODULE_PKG = "com.example.panesunlock";

    /**
     * 格窗宿主里真正的授权闸门：混淆类 wg（默认包），run 在 SystemUI 进程。
     * 设置页显示的“已解锁”只读 Bundle，而功能是否可用全部走 wg.a()（见
     * NeXtepWindowController.paidAllowed / pauseTrialAllows / turnsLandscape）。
     * 这就是“显示已解锁、功能没效果”的根因。
     */
    private static final String PANES_LICENSE_CLASS = "wg";
    /** 格窗授权状态类（默认包），决定 LICENSE_UNLOCKED / 试用有效性；3.4.9 起试用改签名态、走服务端校验。 */
    private static final String PANES_LICENSE_STATE_CLASS = "Leerwybvpn";
    private static final String PANES_CONTROLLER_CLASS =
            "io.github.lujinxin.nextep.systemui.NeXtepWindowController";
    private static final String PANES_TOOLS_CLASS =
            "io.github.lujinxin.nextep.systemui.WorkbenchToolsController";
    /** App 侧场景编辑卡：scenesAllowed() 为 false 时保存≥2场景会弹“格窗试用已结束”对话框。 */
    private static final String PANES_SETTINGS_CARD_CLASS =
            "io.github.lujinxin.nextep.config.WorkbenchSettingsCard";
    /** 已经挂过的类：格窗会用自己的 ClassLoader 多次加载同一份 dex，必须逐个副本都挂。 */
    private static final java.util.Set<Class<?>> LICENSE_HOOKED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Class<?>, Boolean>());
    private static volatile boolean licenseWatcherInstalled = false;

    private static final String K_UNLOCKED = "io.github.yishisanren.panes.extra.LICENSE_UNLOCKED";
    private static final String K_TRIAL = "io.github.yishisanren.panes.extra.LICENSE_TRIAL";
    private static final String K_MACHINE = "io.github.yishisanren.panes.extra.LICENSE_MACHINE";
    private static final long TRIAL_FOREVER = 4102444800000L; // 2100-01-01

    private static List<HookConf.AppEntry> CONFIG;
    private static XSharedPreferences XPREFS;
    private static String LOADED_USER_JSON;
    private static Map<String, List<UserHookStore.HookEntry>> USER_RULES;
    private static Map<String, UserHookStore.AppEntry> USER_APPS;

    static {
        String json = readAssets();
        CONFIG = HookConf.parse(json != null ? json : HookConf.DEFAULT_JSON);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (CONFIG == null) return;
        String pkg = lpparam.packageName;

        // 格窗主机制：覆盖 App 与 SystemUI 宿主进程
        if (PANES.equals(pkg) || SYSTEMUI.equals(pkg)) {
            try {
                Class<?> baseBundle = XposedHelpers.findClass("android.os.BaseBundle", lpparam.classLoader);
                hookBundle(baseBundle);
            } catch (Throwable ignored) {
                // 框架类必然存在
            }
            // Bundle 可能自己重写了这些 getter，单独再 hook 一次，确保 LICENSE_* 强制生效
            try {
                Class<?> bundle = XposedHelpers.findClass("android.os.Bundle", lpparam.classLoader);
                hookBundle(bundle);
            } catch (Throwable ignored) {
            }
            // 真正放行功能：SystemUI 里的 wg 授权校验
            hookPanesLicense(lpparam);
            // 方案 B：隐藏本模块包，使格窗的模块检测枚举不到
            hookHideModulePackage(lpparam);
        }

        // 通用应用规则（可在模块 App 里按包名开关）
        for (HookConf.AppEntry app : CONFIG) {
            if (app.pkg.equals(pkg)) {
                if (PANES.equals(pkg)) injectTrialToken();
                if (builtinEnabled(pkg)) {
                    for (HookConf.Rule r : app.rules) {
                        if (!"bundle_forge".equals(r.type)) {
                            applyRule(r, lpparam);
                        }
                    }
                }
                break;
            }
        }

        // 用户自定义 hook（拓展页配置，SimpleHook 式）
        List<UserHookStore.HookEntry> userRules = userRulesFor(pkg);
        UserHookStore.AppEntry appSettings = USER_APPS == null ? null : USER_APPS.get(pkg);
        if (appSettings == null && (userRules == null || userRules.isEmpty())) return;

        XposedBridge.log("[PanesUnlock] " + pkg + " 已注入，应用 "
                + (userRules == null ? 0 : userRules.size()) + " 条拓展 hook");
        if (appSettings != null) applyAppToggles(appSettings, lpparam);
        if (userRules != null) {
            for (UserHookStore.HookEntry h : userRules) {
                applyUserRule(h, lpparam);
            }
        }
    }

    // ---------------- 用户自定义 hook（拓展） ----------------

    private static XSharedPreferences xPrefs() {
        if (XPREFS == null) {
            try {
                XPREFS = new XSharedPreferences("com.example.panesunlock", UserHookStore.PREFS);
                XPREFS.makeWorldReadable();
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            XPREFS.reload();
        } catch (Throwable ignored) {
        }
        return XPREFS;
    }

    private static boolean builtinEnabled(String pkg) {
        XSharedPreferences p = xPrefs();
        return p == null || p.getBoolean("enabled_" + pkg, true);
    }

    private static List<UserHookStore.HookEntry> userRulesFor(String pkg) {
        try {
            XSharedPreferences p = xPrefs();
            if (p == null) {
                XposedBridge.log("[PanesUnlock] 无法读取模块配置（XSharedPreferences 不可用）");
                return null;
            }
            String json = p.getString(UserHookStore.KEY_CONFIG, null);
            if (json == null || json.trim().isEmpty()) return null;
            if (USER_RULES == null || !json.equals(LOADED_USER_JSON)) {
                LOADED_USER_JSON = json;
                List<UserHookStore.AppEntry> apps = UserHookStore.parseConfig(json);
                USER_RULES = UserHookStore.toMap(apps);
                USER_APPS = UserHookStore.toAppMap(apps);
                XposedBridge.log("[PanesUnlock] 配置已加载，共 " + USER_APPS.size() + " 个启用应用");
            }
            List<UserHookStore.HookEntry> rules = USER_RULES.get(pkg);
            if (rules == null) {
                XposedBridge.log("[PanesUnlock] 当前进程 " + pkg + " 不在拓展配置中；已配置: " + USER_RULES.keySet());
            }
            return rules;
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 读取拓展配置异常: " + t);
            return null;
        }
    }

    /** 待类可用后再挂的规则（加固 / 动态 dex 场景下 handleLoadPackage 时类还不存在） */
    private static class PendingRule {
        final UserHookStore.HookEntry h;
        final XC_LoadPackage.LoadPackageParam lp;

        PendingRule(UserHookStore.HookEntry h, XC_LoadPackage.LoadPackageParam lp) {
            this.h = h;
            this.lp = lp;
        }
    }

    private static final List<PendingRule> PENDING_RULES = new ArrayList<>();
    private static boolean classLoaderHooked = false;

    private void applyUserRule(UserHookStore.HookEntry h, XC_LoadPackage.LoadPackageParam lp) {
        if (h == null || h.className == null || h.className.isEmpty()) return;
        if (h.useAttach) {
            hookWhenAttached(h, lp);
            return;
        }
        try {
            Class<?> clazz = XposedHelpers.findClass(h.className, lp.classLoader);
            applyUserRuleToClass(clazz, h, lp);
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 类暂不可用，等待加载: " + h.className);
            hookWhenClassLoaded(h, lp);
        }
    }

    /** ClassLoader 开关：等 Application.attach(BackContext) 后用其 ClassLoader 挂 Hook */
    private static final List<PendingRule> ATTACH_RULES = new ArrayList<>();
    private static boolean attachHooked = false;

    private void hookWhenAttached(UserHookStore.HookEntry h, XC_LoadPackage.LoadPackageParam lp) {
        synchronized (ATTACH_RULES) {
            ATTACH_RULES.add(new PendingRule(h, lp));
            if (attachHooked) return;
            attachHooked = true;
        }
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.app.Application", lp.classLoader),
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.thisObject instanceof Context)) return;
                            ClassLoader cl = ((Context) p.thisObject).getClassLoader();
                            List<PendingRule> todo;
                            synchronized (ATTACH_RULES) {
                                todo = new ArrayList<>(ATTACH_RULES);
                                ATTACH_RULES.clear();
                            }
                            for (PendingRule pr : todo) {
                                try {
                                    Class<?> clazz = XposedHelpers.findClass(pr.h.className, cl);
                                    XposedBridge.log("[PanesUnlock] attach 后 ClassLoader 命中: "
                                            + pr.h.className);
                                    applyUserRuleToClass(clazz, pr.h, pr.lp);
                                } catch (Throwable t) {
                                    hookWhenClassLoaded(pr.h, pr.lp);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 注册 attach Hook 失败: " + t);
        }
    }

    /** 监听 ClassLoader.loadClass，目标类真正被加载时补挂 hook */
    private void hookWhenClassLoaded(UserHookStore.HookEntry h, XC_LoadPackage.LoadPackageParam lp) {
        synchronized (PENDING_RULES) {
            PENDING_RULES.add(new PendingRule(h, lp));
            if (classLoaderHooked) return;
            classLoaderHooked = true;
        }
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (!(r instanceof Class)) return;
                    String name = ((Class<?>) r).getName();
                    List<PendingRule> matched = new ArrayList<>();
                    synchronized (PENDING_RULES) {
                        java.util.Iterator<PendingRule> it = PENDING_RULES.iterator();
                        while (it.hasNext()) {
                            PendingRule pr = it.next();
                            if (name.equals(pr.h.className)) {
                                matched.add(pr);
                                it.remove();
                            }
                        }
                    }
                    for (PendingRule pr : matched) {
                        try {
                            XposedBridge.log("[PanesUnlock] 延迟命中类加载: " + name);
                            applyUserRuleToClass((Class<?>) r, pr.h, pr.lp);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 注册延迟 hook 失败: " + t);
        }
    }

    private void applyUserRuleToClass(Class<?> clazz, UserHookStore.HookEntry h,
                                      XC_LoadPackage.LoadPackageParam lp) {
        try {
            if (UserHookStore.MODE_STATIC.equals(h.mode)) {
                applyStaticField(clazz, h);
                XposedBridge.log("[PanesUnlock] 已 hook 静态变量 " + h.className + "." + h.fieldName);
                return;
            }

            XC_MethodHook hook = makeUserHook(h, lp.packageName);
            Class<?>[] pts = parseParamTypes(h.paramTypes, lp.classLoader);
            if (UserHookStore.MODE_PARAMS.equals(h.mode) && pts == null) {
                return; // 参数替换必须能确定参数类型
            }

            String mn = h.methodName == null ? "" : h.methodName.trim();
            if (mn.isEmpty()) mn = "*"; // 记录类/静态变量等允许不填方法名 → hook 全部方法

            if ("<init>".equals(mn)) {
                if (pts != null) {
                    Object[] args = new Object[pts.length + 1];
                    System.arraycopy(pts, 0, args, 0, pts.length);
                    args[pts.length] = hook;
                    XposedHelpers.findAndHookConstructor(clazz, args);
                } else {
                    XposedBridge.hookAllConstructors(clazz, hook);
                }
                XposedBridge.log("[PanesUnlock] 已 hook 构造 " + h.className);
                return;
            }

            if ("*".equals(mn)) {
                for (Method m : clazz.getDeclaredMethods()) {
                    XposedBridge.hookMethod(m, hook);
                }
                XposedBridge.log("[PanesUnlock] 已 hook 全部方法 " + h.className);
                return;
            }
            if (pts == null) {
                java.util.Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, mn, hook);
                XposedBridge.log("[PanesUnlock] 已 hook " + h.className + "." + mn
                        + "，匹配 " + (hooks == null ? 0 : hooks.size()) + " 个重载");
            } else {
                Object[] args = new Object[pts.length + 1];
                System.arraycopy(pts, 0, args, 0, pts.length);
                args[pts.length] = hook;
                XposedHelpers.findAndHookMethod(clazz, mn, args);
                XposedBridge.log("[PanesUnlock] 已 hook " + h.className + "." + mn + "（精确签名）");
            }
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] hook 失败 " + h.className + "."
                    + h.methodName + " (" + h.mode + "): " + t);
        }
    }

    private void applyStaticField(Class<?> clazz, UserHookStore.HookEntry h) {
        try {
            if (h.fieldName == null || h.fieldName.isEmpty()) return;
            Field f = XposedHelpers.findField(clazz, h.fieldName);
            f.setAccessible(true);
            Object v = UserHookStore.coerce(UserHookStore.parseValue(h.value), f.getType());
            f.set(null, v);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 应用级开关（「配置：xxx」页） ----------------

    /** 应用级检测/网络开关，尽力而为，单项失败不影响其它项 */
    private void applyAppToggles(final UserHookStore.AppEntry a,
                                 XC_LoadPackage.LoadPackageParam lp) {
        if (a.toast) toastInTarget(a.label);
        if (a.blockAccessibility) {
            hookSettingsInt("android.provider.Settings$Secure", lp,
                    "accessibility_enabled", 0);
            hookSettingsString("android.provider.Settings$Secure", lp,
                    "enabled_accessibility_services", "");
            try {
                Class<?> am = XposedHelpers.findClass(
                        "android.view.accessibility.AccessibilityManager", lp.classLoader);
                XposedBridge.hookAllMethods(am, "getEnabledAccessibilityServiceList",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                p.setResult(new java.util.ArrayList<>());
                            }
                        });
            } catch (Throwable ignored) {
            }
        }
        if (a.blockDevOptions) {
            hookSettingsInt("android.provider.Settings$Global", lp,
                    "development_settings_enabled", 0);
        }
        if (a.blockUsbDebug) {
            hookSettingsInt("android.provider.Settings$Global", lp, "adb_enabled", 0);
        }
        if (a.blockVm || a.blockEnv) {
            hookSettingsInt("android.provider.Settings$Global", lp, "adb_enabled", 0);
            try {
                XposedHelpers.findAndHookMethod("android.os.SystemProperties", lp.classLoader,
                        "get", String.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                Object k = p.args.length > 0 ? p.args[0] : null;
                                if (k instanceof String && suspicious((String) k)) p.setResult("");
                            }
                        });
            } catch (Throwable ignored) {
            }
            // 仅对明确的 Root / Xposed 特征路径返回 false，避免误伤普通文件路径
            try {
                XposedHelpers.findAndHookMethod("java.io.File", lp.classLoader, "exists",
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    String path = ((java.io.File) p.thisObject)
                                            .getAbsolutePath().toLowerCase();
                                    for (String marker : ROOT_MARKERS) {
                                        if (path.contains(marker)) {
                                            p.setResult(false);
                                            return;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
            } catch (Throwable ignored) {
            }
        }
        if (a.hideVpn && !a.hideProxy) {
            try {
                Class<?> nc = XposedHelpers.findClass(
                        "android.net.NetworkCapabilities", lp.classLoader);
                XposedBridge.hookAllMethods(nc, "hasTransport", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        // NetworkCapabilities.TRANSPORT_VPN = 4
                        if (p.args.length > 0 && p.args[0] instanceof Integer
                                && (Integer) p.args[0] == 4) {
                            p.setResult(false);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static final String[] ROOT_MARKERS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/magisk/", "magiskhide", "/data/adb/magisk",
            "xposedbridge", "lsposed", "riru", "zygisk"
    };

    private static boolean suspicious(String key) {
        String k = key.toLowerCase();
        return k.contains("xposed") || k.contains("magisk") || k.contains("riru")
                || k.contains("debug") || k.contains("emulator") || k.contains("goldfish");
    }

    private void hookSettingsInt(String cls, XC_LoadPackage.LoadPackageParam lp,
                                 final String key, final int value) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, lp.classLoader);
            XposedBridge.hookAllMethods(c, "getInt", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length > 1 && p.args[1] instanceof String
                            && key.equals(p.args[1])) {
                        p.setResult(value);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookSettingsString(String cls, XC_LoadPackage.LoadPackageParam lp,
                                    final String key, final String value) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, lp.classLoader);
            XposedBridge.hookAllMethods(c, "getString", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length > 1 && p.args[1] instanceof String
                            && key.equals(p.args[1])) {
                        p.setResult(value);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void toastInTarget(final String label) {
        try {
            final Context ctx = currentApplication();
            if (ctx == null) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(ctx, "Panes Unlock 已生效：" + label,
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private XC_MethodHook makeUserHook(final UserHookStore.HookEntry h, final String pkg) {
        return new XC_MethodHook() {
            private String tag() {
                return "[PanesUnlock][" + pkg + "] " + h.className + "."
                        + (h.methodName == null || h.methodName.isEmpty() ? "*" : h.methodName);
            }

            private Class<?> returnType(MethodHookParam p) {
                if (h.returnType != null && !h.returnType.trim().isEmpty()) {
                    String t = h.returnType.trim();
                    Class<?> c = primitiveFor(t);
                    if (c != null) return c;
                    try {
                        return XposedHelpers.findClass(t,
                                ((Member) p.method).getDeclaringClass().getClassLoader());
                    } catch (Throwable ignored) {
                    }
                }
                return (p.method instanceof Method)
                        ? ((Method) p.method).getReturnType()
                        : (p.method instanceof Constructor ? void.class : Object.class);
            }

            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Class<?> rt = returnType(p);
                    String m = h.mode;
                    if (UserHookStore.MODE_RETURN.equals(m)) {
                        p.setResult(UserHookStore.coerce(UserHookStore.parseValue(h.value), rt));
                    } else if (UserHookStore.MODE_INTERCEPT.equals(m)) {
                        p.setResult(UserHookStore.defaultValueFor(rt));
                    } else if (UserHookStore.MODE_PARAMS.equals(m)) {
                        String[] vals = h.value.split(",", -1);
                        for (int i = 0; i < vals.length && i < p.args.length; i++) {
                            String s = vals[i].trim();
                            if (s.isEmpty()) continue;
                            Object parsed = UserHookStore.parseValue(s);
                            p.args[i] = (p.args[i] != null)
                                    ? UserHookStore.coerce(parsed, p.args[i].getClass())
                                    : parsed;
                        }
                    } else if (UserHookStore.MODE_LOG_PARAMS.equals(m) || UserHookStore.MODE_LOG_BOTH.equals(m)) {
                        StringBuilder sb = new StringBuilder(tag() + " 参数: ");
                        for (int i = 0; i < p.args.length; i++) {
                            sb.append("arg").append(i).append("=").append(p.args[i]).append(" ");
                        }
                        XposedBridge.log(sb.toString());
                    } else if (UserHookStore.MODE_LOG_STATIC.equals(m)) {
                        try {
                            Field f = XposedHelpers.findField(((Member) p.method).getDeclaringClass(), h.fieldName);
                            f.setAccessible(true);
                            XposedBridge.log(tag() + " 静态字段 " + h.fieldName + " = " + f.get(null));
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    String m = h.mode;
                    if (UserHookStore.MODE_RETURN_AFTER.equals(m)) {
                        p.setResult(UserHookStore.coerce(UserHookStore.parseValue(h.value),
                                returnType(p)));
                    } else if (UserHookStore.MODE_INSTANCE.equals(m)) {
                        if (p.thisObject != null && h.fieldName != null && !h.fieldName.isEmpty()) {
                            Field f = XposedHelpers.findField(p.thisObject.getClass(), h.fieldName);
                            f.setAccessible(true);
                            f.set(p.thisObject, UserHookStore.coerce(UserHookStore.parseValue(h.value), f.getType()));
                        }
                    } else if (UserHookStore.MODE_LOG_RETURN.equals(m) || UserHookStore.MODE_LOG_BOTH.equals(m)) {
                        XposedBridge.log(tag() + " 返回: " + p.getResult());
                    } else if (UserHookStore.MODE_LOG_INSTANCE.equals(m)) {
                        if (p.thisObject != null && h.fieldName != null && !h.fieldName.isEmpty()) {
                            Field f = XposedHelpers.findField(p.thisObject.getClass(), h.fieldName);
                            f.setAccessible(true);
                            XposedBridge.log(tag() + " 实例字段 " + h.fieldName + " = " + f.get(p.thisObject));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static Class<?>[] parseParamTypes(String s, ClassLoader cl) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "*".equals(t)) return null;
        String[] parts = t.split(",");
        List<Class<?>> out = new ArrayList<>();
        for (String raw : parts) {
            String n = raw.trim();
            if (n.isEmpty()) continue;
            Class<?> c = primitiveFor(n);
            if (c == null) {
                try {
                    c = XposedHelpers.findClass(n, cl);
                } catch (Throwable e) {
                    return null; // 任一类型找不到则退化为全部重载
                }
            }
            out.add(c);
        }
        return out.toArray(new Class<?>[0]);
    }

    private static Class<?> primitiveFor(String n) {
        switch (n) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "double": return double.class;
            case "short": return short.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "void": return void.class;
            case "byte[]": return byte[].class;
            case "int[]": return int[].class;
            case "long[]": return long[].class;
            case "String": case "string": return String.class;
            default: return null;
        }
    }

    /** 反射取 Application（避免依赖 Xposed 实现包的 AndroidAppHelper）。 */
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 仅在格窗进程内：注入合法永久试用令牌（best-effort）。 */
    private void injectTrialToken() {
        try {
            Context ctx = currentApplication();
            if (ctx != null) {
                String token = TrialToken.generate();
                Settings.Global.putString(ctx.getContentResolver(), "panes_trial_v1", token);
                Settings.Secure.putString(ctx.getContentResolver(), "sysui_tile_layout_rev", token);
            }
        } catch (Throwable ignored) {
            // 权限不足等情况下跳过，不影响主机制
        }
    }

    /** 强制 BaseBundle 读取（格窗解锁主机制）。 */
    private void hookBundle(Class<?> baseBundle) {
        XC_MethodHook boolHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                String k = (String) p.args[0];
                if (K_UNLOCKED.equals(k) || K_TRIAL.equals(k)) {
                    p.setResult(true);
                }
            }
        };
        XC_MethodHook longHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (K_TRIAL.equals(p.args[0])) {
                    p.setResult(TRIAL_FOREVER);
                }
            }
        };
        XC_MethodHook strHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (K_MACHINE.equals(p.args[0]) && p.getResult() == null) {
                    p.setResult("UNLOCKED");
                }
            }
        };

        hookIfPresent(baseBundle, boolHook, "containsKey", String.class);
        hookIfPresent(baseBundle, boolHook, "getBoolean", String.class);
        hookIfPresent(baseBundle, boolHook, "getBoolean", String.class, boolean.class);
        hookIfPresent(baseBundle, longHook, "getLong", String.class);
        hookIfPresent(baseBundle, longHook, "getLong", String.class, long.class);
        hookIfPresent(baseBundle, strHook, "getString", String.class);
        hookIfPresent(baseBundle, strHook, "getString", String.class, String.class);
    }

    /** 逐个方法 try/catch：Bundle 与 BaseBundle 可能各自声明这些 getter，缺失不影响其它。 */
    private static void hookIfPresent(Class<?> c, XC_MethodHook h, String name, Class<?>... pts) {
        try {
            Object[] args = new Object[pts.length + 1];
            System.arraycopy(pts, 0, args, 0, pts.length);
            args[pts.length] = h;
            XposedHelpers.findAndHookMethod(c, name, args);
        } catch (Throwable ignored) {
            // 该方法未在此类声明（由父类提供）时跳过
        }
    }

    // ---------------- 格窗真正的授权闸门（SystemUI 侧 wg） ----------------

    /**
     * 放行格窗宿主里的授权校验。
     *
     * wg 是格窗模块注入 SystemUI 后才加载的类，且往往在另一个 ClassLoader 里，
     * handleLoadPackage 阶段通常还不可见；因此先直取，取不到就监听类加载。
     * 只把 wg.a()（功能是否放行）和 wg.c()（是否已购买）强制为 true。
     */
    private void hookPanesLicense(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.log("[PanesUnlock] 已注入进程 " + lp.packageName + " / " + lp.processName);
        for (String name : new String[]{
                PANES_LICENSE_CLASS, PANES_LICENSE_STATE_CLASS,
                PANES_CONTROLLER_CLASS, PANES_TOOLS_CLASS, PANES_SETTINGS_CARD_CLASS}) {
            try {
                installForClass(XposedHelpers.findClass(name, lp.classLoader));
            } catch (Throwable ignored) {
                // 目标类尚未加载，转由下面的类加载监听补挂
            }
        }
        installLicenseClassWatcher();
    }

    /** 按类名分派到对应的 hook 安装逻辑。 */
    private static void installForClass(Class<?> c) {
        if (c == null) return;
        String n = c.getName();
        if (PANES_LICENSE_CLASS.equals(n)) {
            installLicenseHooks(c);
        } else if (PANES_CONTROLLER_CLASS.equals(n)) {
            hookController(c);
        } else if (PANES_LICENSE_STATE_CLASS.equals(n)) {
            hookLicenseManager(c);
        } else if (PANES_TOOLS_CLASS.equals(n)) {
            hookTools(c);
        } else if (PANES_SETTINGS_CARD_CLASS.equals(n)) {
            hookScenesGate(c);
        }
    }

    private void installLicenseClassWatcher() {
        synchronized (MultiAppHookModule.class) {
            if (licenseWatcherInstalled) return;
            licenseWatcherInstalled = true;
        }
        try {
            XC_MethodHook onLoaded = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (!(r instanceof Class)) return;
                    installForClass((Class<?>) r);
                }
            };
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", onLoaded);
            // 少数注入实现绕过 loadClass 直接走 findClass，这里补一层
            Class<?> dexLoader = XposedHelpers.findClass("dalvik.system.BaseDexClassLoader", null);
            XposedBridge.hookAllMethods(dexLoader, "findClass", onLoaded);
            XposedBridge.log("[PanesUnlock] 已监听格窗宿主类的加载");
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 注册授权类监听失败: " + t);
        }
    }

    /**
     * 方案 B：在格窗 App / SystemUI 宿主进程里，把本模块包（com.example.panesunlock）
     * 从 PackageManager 的查询结果中剔除。格窗的篡改检测
     * （Liinhapqy.gmqotxpug）正是通过 getInstalledApplications 枚举已装应用、
     * 再检查 xposedmodule + xposedscope 来发现本模块的——只要枚举不到，就不会报警。
     * 即使未来格窗改成"凡 xposedmodule 即报警"也能挡住（因为包本身被隐藏）。
     */
    private static void hookHideModulePackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> apm = XposedHelpers.findClass(
                    "android.app.ApplicationPackageManager", lp.classLoader);
            XC_MethodHook filter = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    if (!(r instanceof List)) return;
                    List<?> list = (List<?>) r;
                    try {
                        list.removeIf(o -> o != null && MODULE_PKG.equals(
                                XposedHelpers.getObjectField(o, "packageName")));
                    } catch (Throwable ignored) {
                        // 个别 ROM 返回不可变列表，忽略即可
                    }
                }
            };
            XposedBridge.hookAllMethods(apm, "getInstalledApplications", filter);
            XposedBridge.hookAllMethods(apm, "getInstalledPackages", filter);
            // getPackageInfo 直接抛 NameNotFound，让检测拿不到本模块信息
            XposedBridge.hookAllMethods(apm, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && MODULE_PKG.equals(p.args[0])) {
                        p.setThrowable(
                                new android.content.pm.PackageManager.NameNotFoundException(MODULE_PKG));
                    }
                }
            });
            XposedBridge.log("[PanesUnlock] 已隐藏模块包 " + MODULE_PKG
                    + "（防格窗篡改检测），进程 " + lp.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 隐藏模块包失败: " + t);
        }
    }

    private static synchronized void installLicenseHooks(Class<?> clazz) {
        if (clazz == null || LICENSE_HOOKED.contains(clazz)) return;
        // 结构校验：wg 拥有 a()Z / c()Z / f()Z 三个无参布尔方法，避免短类名误撞
        boolean hasA = false, hasC = false, hasF = false;
        List<Method> targets = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != boolean.class) continue;
            String n = m.getName();
            if ("a".equals(n)) {
                hasA = true;
                targets.add(m);
            } else if ("c".equals(n)) {
                hasC = true;
                targets.add(m);
            } else if ("f".equals(n)) {
                hasF = true;
            }
        }
        if (!hasA || !hasC || !hasF) {
            XposedBridge.log("[PanesUnlock] " + clazz.getName() + " 结构不匹配，跳过");
            return;
        }
        LICENSE_HOOKED.add(clazz);
        XC_MethodHook forceTrue = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                p.setResult(Boolean.TRUE);
            }
        };
        int ok = 0;
        for (Method m : targets) {
            try {
                XposedBridge.hookMethod(m, forceTrue);
                ok++;
            } catch (Throwable t) {
                XposedBridge.log("[PanesUnlock] hook " + m + " 失败: " + t);
            }
        }
        XposedBridge.log("[PanesUnlock] 授权闸门已放行: " + clazz.getName()
                + "（" + ok + " 个方法，loader=" + clazz.getClassLoader() + "）");
    }

    /** 兜底：直接放行宿主自己的付费判断，不依赖 wg 是否被挂上。 */
    private static synchronized void hookController(Class<?> clazz) {
        if (clazz == null || LICENSE_HOOKED.contains(clazz)) return;
        LICENSE_HOOKED.add(clazz);
        XC_MethodHook forceTrue = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                // before 直接短路：既放行功能，又避免 paidAllowed 内部把解锁弹框 post 出去
                p.setResult(Boolean.TRUE);
            }
        };
        int ok = 0;
        for (String name : new String[]{"paidAllowed", "pauseTrialAllows", "turnsLandscape"}) {
            try {
                XposedBridge.hookAllMethods(clazz, name, forceTrue);
                ok++;
            } catch (Throwable t) {
                XposedBridge.log("[PanesUnlock] hook " + name + " 失败: " + t);
            }
        }
        XposedBridge.log("[PanesUnlock] 宿主付费判断已放行 " + ok + " 个方法（"
                + clazz.getClassLoader() + "）");
    }

    /** 兜底：解锁提示弹窗直接不弹，避免挡住任务界面。 */
    private static synchronized void hookTools(Class<?> clazz) {
        if (clazz == null || LICENSE_HOOKED.contains(clazz)) return;
        LICENSE_HOOKED.add(clazz);
        XC_MethodHook skip = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(null);
            }
        };
        try {
            XposedBridge.hookAllMethods(clazz, "showUnlockPrompt", skip);
            XposedBridge.log("[PanesUnlock] 解锁提示已屏蔽（" + clazz.getClassLoader() + "）");
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] 屏蔽解锁提示失败: " + t);
        }
    }

    /**
     * App 侧场景编辑卡：强制 scenesAllowed()=true。
     * SceneComposerDialog.save() 在“场景数≥2 且 scenesAllowed()==false”时会弹
     * “格窗试用已结束”对话框（unlock_prompt_title/message），短路此方法即可彻底关闭。
     */
    private static synchronized void hookScenesGate(Class<?> clazz) {
        if (clazz == null || LICENSE_HOOKED.contains(clazz)) return;
        LICENSE_HOOKED.add(clazz);
        XC_MethodHook forceTrue = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(Boolean.TRUE);
            }
        };
        int ok = 0;
        for (String name : new String[]{"scenesAllowed", "access$scenesAllowed"}) {
            try {
                XposedBridge.hookAllMethods(clazz, name, forceTrue);
                ok++;
            } catch (Throwable t) {
                XposedBridge.log("[PanesUnlock] hook " + name + " 失败: " + t);
            }
        }
        XposedBridge.log("[PanesUnlock] 多场景闸门已放行 " + ok + " 个方法（"
                + clazz.getClassLoader() + "）");
    }

    /**
     * 格窗授权状态类 Leerwybvpn（3.4.9）：直接强制其两个状态方法，使“已解锁 / 试用有效”恒成立。
     *  - vcgrsm() 返回解锁态 int，0=未解锁；非 0 时 workbenchState() 会把 LICENSE_UNLOCKED 置 true，
     *    且 SystemUI 的试用弹窗（trialOnOpen）会直接跳过。用 before 短路，避免其结果被缓存回 0。
     *  - pkakbmyyn() 返回试用有效性；非 0 态下本就为 true，这里兜底再强制一次。
     * 这样 3.4.9 的“试用已结束”不会再出现，设置页显示已解锁。
     */
    private static synchronized void hookLicenseManager(Class<?> clazz) {
        if (clazz == null || LICENSE_HOOKED.contains(clazz)) return;
        LICENSE_HOOKED.add(clazz);
        XC_MethodHook vcgrsmHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(Integer.valueOf(2)); // 非 0 即代表已授权
            }
        };
        XC_MethodHook trialHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(Boolean.TRUE);
            }
        };
        int ok = 0;
        try {
            XposedBridge.hookAllMethods(clazz, "vcgrsm", vcgrsmHook);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] hook vcgrsm 失败: " + t);
        }
        try {
            XposedBridge.hookAllMethods(clazz, "pkakbmyyn", trialHook);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log("[PanesUnlock] hook pkakbmyyn 失败: " + t);
        }
        XposedBridge.log("[PanesUnlock] 授权状态类已强制已解锁 " + clazz.getName()
                + "（" + ok + " 个方法，" + clazz.getClassLoader() + "）");
    }

    /** 应用一条通用规则（simplehook 式）。 */
    private void applyRule(HookConf.Rule rule, XC_LoadPackage.LoadPackageParam lp) {
        if (rule.cls == null || rule.cls.isEmpty()) {
            return;
        }
        // field_true：hook 该类的所有构造方法，构造后把指定布尔字段强制为 true
        if ("field_true".equals(rule.type)) {
            try {
                Class<?> c = XposedHelpers.findClass(rule.cls, lp.classLoader);
                XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            XposedHelpers.setBooleanField(p.thisObject, rule.field, true);
                        } catch (Throwable ignored) {
                            // 字段不存在时跳过
                        }
                    }
                });
            } catch (Throwable ignored) {
                // 类在当前版本不存在则跳过
            }
            return;
        }
        if (rule.method == null || rule.method.isEmpty()) {
            return;
        }
        Class<?>[] pts = paramTypesFrom(rule.params);
        XC_MethodHook hook = makeResultHook(rule);
        Object[] args = new Object[pts.length + 1];
        System.arraycopy(pts, 0, args, 0, pts.length);
        args[pts.length] = hook;
        try {
            XposedHelpers.findAndHookMethod(rule.cls, lp.classLoader, rule.method, args);
        } catch (Throwable ignored) {
            // 类/方法在当前版本不存在则跳过
        }
    }

    private XC_MethodHook makeResultHook(HookConf.Rule rule) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    switch (rule.type) {
                        case "return_true":
                            p.setResult(true);
                            break;
                        case "return_false":
                            p.setResult(false);
                            break;
                        case "return_null":
                            p.setResult(null);
                            break;
                        case "return_int":
                            p.setResult(Integer.parseInt(rule.value));
                            break;
                        case "return_string":
                            p.setResult(rule.value);
                            break;
                        default:
                            break;
                    }
                } catch (Throwable ignored) {
                    // 返回值类型不匹配时忽略
                }
            }
        };
    }

    private static Class<?>[] paramTypesFrom(List<String> params) {
        Class<?>[] out = new Class<?>[params.size()];
        for (int i = 0; i < params.size(); i++) {
            String t = params.get(i);
            if ("boolean".equals(t)) out[i] = boolean.class;
            else if ("int".equals(t)) out[i] = int.class;
            else if ("long".equals(t)) out[i] = long.class;
            else if ("float".equals(t)) out[i] = float.class;
            else if ("double".equals(t)) out[i] = double.class;
            else if ("java.lang.String".equals(t) || "String".equals(t)) out[i] = String.class;
            else {
                try {
                    out[i] = Class.forName(t, false, MultiAppHookModule.class.getClassLoader());
                } catch (Throwable e) {
                    out[i] = Object.class;
                }
            }
        }
        return out;
    }

    /** 从模块自身 APK 的 assets 读取 hooks.json（失败返回 null，由调用方回退 DEFAULT_JSON）。 */
    private static String readAssets() {
        try {
            InputStream is = MultiAppHookModule.class.getClassLoader()
                    .getResourceAsStream("assets/hooks.json");
            if (is != null) {
                String s = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
                is.close();
                return s;
            }
        } catch (Throwable t) {
            // 资源不可用时回退
        }
        return null;
    }
}
