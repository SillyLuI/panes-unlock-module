package com.example.panesunlock;

import android.content.Context;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.InputStream;
import java.util.List;
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

    private static final String K_UNLOCKED = "io.github.yishisanren.panes.extra.LICENSE_UNLOCKED";
    private static final String K_TRIAL = "io.github.yishisanren.panes.extra.LICENSE_TRIAL";
    private static final String K_MACHINE = "io.github.yishisanren.panes.extra.LICENSE_MACHINE";
    private static final long TRIAL_FOREVER = 4102444800000L; // 2100-01-01

    private static List<HookConf.AppEntry> CONFIG;

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
        }

        // 通用应用规则
        for (HookConf.AppEntry app : CONFIG) {
            if (app.pkg.equals(pkg)) {
                if (PANES.equals(pkg)) injectTrialToken();
                for (HookConf.Rule r : app.rules) {
                    if (!"bundle_forge".equals(r.type)) {
                        applyRule(r, lpparam);
                    }
                }
                break;
            }
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

        XposedHelpers.findAndHookMethod(baseBundle, "containsKey", String.class, boolHook);
        XposedHelpers.findAndHookMethod(baseBundle, "getBoolean", String.class, boolHook);
        XposedHelpers.findAndHookMethod(baseBundle, "getBoolean", String.class, boolean.class, boolHook);
        XposedHelpers.findAndHookMethod(baseBundle, "getLong", String.class, longHook);
        XposedHelpers.findAndHookMethod(baseBundle, "getLong", String.class, long.class, longHook);
        XposedHelpers.findAndHookMethod(baseBundle, "getString", String.class, strHook);
        XposedHelpers.findAndHookMethod(baseBundle, "getString", String.class, String.class, strHook);
    }

    /** 应用一条通用规则（simplehook 式）。 */
    private void applyRule(HookConf.Rule rule, XC_LoadPackage.LoadPackageParam lp) {
        if (rule.cls == null || rule.cls.isEmpty()
                || rule.method == null || rule.method.isEmpty()) {
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
