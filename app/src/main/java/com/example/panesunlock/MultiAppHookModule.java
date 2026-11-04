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

    private static final String K_UNLOCKED = "io.github.yishisanren.panes.extra.LICENSE_UNLOCKED";
    private static final String K_TRIAL = "io.github.yishisanren.panes.extra.LICENSE_TRIAL";
    private static final String K_MACHINE = "io.github.yishisanren.panes.extra.LICENSE_MACHINE";
    private static final long TRIAL_FOREVER = 4102444800000L; // 2100-01-01

    private static List<HookConf.AppEntry> CONFIG;
    private static XSharedPreferences XPREFS;
    private static String LOADED_USER_JSON;
    private static Map<String, List<UserHookStore.HookEntry>> USER_RULES;

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
            if (p == null) return null;
            String json = p.getString(UserHookStore.KEY_CONFIG, null);
            if (json == null || json.trim().isEmpty()) return null;
            if (USER_RULES == null || !json.equals(LOADED_USER_JSON)) {
                LOADED_USER_JSON = json;
                USER_RULES = UserHookStore.toMap(UserHookStore.parseConfig(json));
            }
            return USER_RULES.get(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyUserRule(UserHookStore.HookEntry h, XC_LoadPackage.LoadPackageParam lp) {
        try {
            if (h == null || h.className == null || h.className.isEmpty()) return;
            Class<?> clazz = XposedHelpers.findClass(h.className, lp.classLoader);

            if (UserHookStore.MODE_STATIC.equals(h.mode)) {
                applyStaticField(clazz, h);
                return;
            }

            XC_MethodHook hook = makeUserHook(h);
            Class<?>[] pts = parseParamTypes(h.paramTypes, lp.classLoader);
            if (UserHookStore.MODE_PARAMS.equals(h.mode) && pts == null) {
                return; // 参数替换必须能确定参数类型
            }

            if ("<init>".equals(h.methodName)) {
                if (pts != null) {
                    Object[] args = new Object[pts.length + 1];
                    System.arraycopy(pts, 0, args, 0, pts.length);
                    args[pts.length] = hook;
                    XposedHelpers.findAndHookConstructor(clazz, args);
                } else {
                    XposedBridge.hookAllConstructors(clazz, hook);
                }
                return;
            }

            if ("*".equals(h.methodName)) {
                for (Method m : clazz.getDeclaredMethods()) {
                    XposedBridge.hookMethod(m, hook);
                }
                return;
            }
            if (pts == null) {
                XposedBridge.hookAllMethods(clazz, h.methodName, hook);
            } else {
                Object[] args = new Object[pts.length + 1];
                System.arraycopy(pts, 0, args, 0, pts.length);
                args[pts.length] = hook;
                XposedHelpers.findAndHookMethod(clazz, h.methodName, args);
            }
        } catch (Throwable ignored) {
            // 类/方法不存在时静默跳过
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

    private XC_MethodHook makeUserHook(final UserHookStore.HookEntry h) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Class<?> rt = (p.method instanceof Method)
                            ? ((Method) p.method).getReturnType()
                            : (p.method instanceof Constructor ? void.class : Object.class);
                    if (UserHookStore.MODE_RETURN.equals(h.mode)) {
                        p.setResult(UserHookStore.coerce(UserHookStore.parseValue(h.value), rt));
                    } else if (UserHookStore.MODE_INTERCEPT.equals(h.mode)) {
                        p.setResult(UserHookStore.defaultValueFor(rt));
                    } else if (UserHookStore.MODE_PARAMS.equals(h.mode)) {
                        String[] vals = h.value.split(",", -1);
                        for (int i = 0; i < vals.length && i < p.args.length; i++) {
                            String s = vals[i].trim();
                            if (s.isEmpty()) continue;
                            Object parsed = UserHookStore.parseValue(s);
                            p.args[i] = (p.args[i] != null)
                                    ? UserHookStore.coerce(parsed, p.args[i].getClass())
                                    : parsed;
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
