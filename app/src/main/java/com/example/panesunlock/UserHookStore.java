package com.example.panesunlock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 用户自定义 hook 配置存储（SimpleHook 式）。
 * 存储在模块应用的 SharedPreferences（user_hooks.xml），Xposed 侧通过 XSharedPreferences 读取。
 */
public class UserHookStore {

    public static final String PREFS = "user_hooks";
    public static final String KEY_CONFIG = "config_json";

    /** 模式常量 */
    public static final String MODE_RETURN = "return_value";
    public static final String MODE_PARAMS = "params";
    public static final String MODE_INTERCEPT = "intercept";
    public static final String MODE_STATIC = "static_field";

    /** “空值”标记：按目标类型转成 0/false/""/空集合 */
    public static final Object EMPTY = new Object();

    public static class HookEntry {
        public String mode = MODE_RETURN;
        public String className = "";
        public String methodName = "";
        public String paramTypes = "";
        public String value = "";
        public String fieldName = "";
    }

    public static class AppEntry {
        public String pkg = "";
        public String label = "";
        public boolean enabled = true;
        public List<HookEntry> hooks = new ArrayList<>();
    }

    // ---------------- 模块应用侧 ----------------

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<AppEntry> load(Context ctx) {
        String json = prefs(ctx).getString(KEY_CONFIG, null);
        return parseConfig(json);
    }

    public static void save(Context ctx, List<AppEntry> apps) {
        try {
            prefs(ctx).edit().putString(KEY_CONFIG, toJson(apps)).apply();
        } catch (Throwable ignored) {
        }
        makeWorldReadable(ctx);
    }

    public static void setBuiltinEnabled(Context ctx, String pkg, boolean enabled) {
        prefs(ctx).edit().putBoolean("enabled_" + pkg, enabled).apply();
        makeWorldReadable(ctx);
    }

    public static boolean isBuiltinEnabled(Context ctx, String pkg, boolean def) {
        return prefs(ctx).getBoolean("enabled_" + pkg, def);
    }

    public static String toJson(List<AppEntry> apps) {
        try {
            JSONArray arr = new JSONArray();
            if (apps != null) {
                for (AppEntry a : apps) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", a.pkg);
                    o.put("label", a.label);
                    o.put("enabled", a.enabled);
                    JSONArray hs = new JSONArray();
                    for (HookEntry h : a.hooks) {
                        JSONObject ho = new JSONObject();
                        ho.put("mode", h.mode);
                        ho.put("className", h.className);
                        ho.put("methodName", h.methodName);
                        ho.put("paramTypes", h.paramTypes);
                        ho.put("value", h.value);
                        ho.put("fieldName", h.fieldName);
                        hs.put(ho);
                    }
                    o.put("hooks", hs);
                    arr.put(o);
                }
            }
            JSONObject root = new JSONObject();
            root.put("apps", arr);
            return root.toString();
        } catch (Throwable t) {
            return "{\"apps\":[]}";
        }
    }

    /** 让 Xposed 侧可通过 XSharedPreferences 读取 */
    public static void makeWorldReadable(Context ctx) {
        try {
            File dataDir = new File(ctx.getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, PREFS + ".xml");
            dataDir.setExecutable(true, false);
            prefsDir.setExecutable(true, false);
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 解析（应用侧与 Xposed 侧共用） ----------------

    public static List<AppEntry> parseConfig(String json) {
        List<AppEntry> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            String s = json.trim();
            if (s.startsWith("[")) {
                // 数组：可能是应用数组
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    AppEntry a = parseApp(arr.optJSONObject(i));
                    if (a != null) out.add(a);
                }
            } else {
                JSONObject root = new JSONObject(s);
                JSONArray apps = root.optJSONArray("apps");
                if (apps != null) {
                    for (int i = 0; i < apps.length(); i++) {
                        AppEntry a = parseApp(apps.optJSONObject(i));
                        if (a != null) out.add(a);
                    }
                } else {
                    // 兼容：键为包名、值为 hook 数组的扁平格式
                    Iterator<String> keys = root.keys();
                    while (keys.hasNext()) {
                        String pkg = keys.next();
                        Object v = root.opt(pkg);
                        if (!(v instanceof JSONArray)) continue;
                        AppEntry a = new AppEntry();
                        a.pkg = pkg;
                        a.label = pkg;
                        JSONArray hs = (JSONArray) v;
                        for (int i = 0; i < hs.length(); i++) {
                            HookEntry h = parseHook(hs.optJSONObject(i));
                            if (h != null) a.hooks.add(h);
                        }
                        if (!a.hooks.isEmpty()) out.add(a);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static AppEntry parseApp(JSONObject o) {
        if (o == null) return null;
        AppEntry a = new AppEntry();
        a.pkg = firstNonEmpty(o, "pkg", "packageName", "package", "appPackage");
        a.label = firstNonEmpty(o, "label", "name", "appName", "appName");
        if (a.label.isEmpty()) a.label = a.pkg;
        a.enabled = o.optBoolean("enabled", true);
        JSONArray hs = o.optJSONArray("hooks");
        if (hs == null) hs = o.optJSONArray("hookList");
        if (hs == null) hs = o.optJSONArray("configs");
        if (hs != null) {
            for (int i = 0; i < hs.length(); i++) {
                HookEntry h = parseHook(hs.optJSONObject(i));
                if (h != null) a.hooks.add(h);
            }
        }
        if (a.pkg.isEmpty() || a.hooks.isEmpty()) return null;
        return a;
    }

    /** 宽容解析单条 hook：兼容 SimpleHook 常见字段名与中文模式名 */
    private static HookEntry parseHook(JSONObject o) {
        if (o == null) return null;
        HookEntry h = new HookEntry();
        h.className = firstNonEmpty(o, "className", "class", "cls");
        h.methodName = firstNonEmpty(o, "methodName", "method");
        String pt = firstNonEmpty(o, "paramTypes", "params", "parameterTypes", "paramType");
        if (pt.isEmpty()) {
            JSONArray pa = o.optJSONArray("paramTypes");
            if (pa == null) pa = o.optJSONArray("params");
            if (pa != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pa.length(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(pa.optString(i, ""));
                }
                pt = sb.toString();
            }
        }
        h.paramTypes = pt;
        h.value = firstNonEmpty(o, "value", "modifyValue", "returnValue", "newValue");
        h.fieldName = firstNonEmpty(o, "fieldName", "staticField", "field");
        h.mode = normalizeMode(firstNonEmpty(o, "mode", "hookMode", "type"));
        if (h.mode.isEmpty()) h.mode = h.value.isEmpty() ? MODE_INTERCEPT : MODE_RETURN;
        if (h.className.isEmpty()) return null;
        if (h.methodName.isEmpty() && !MODE_STATIC.equals(h.mode)) return null;
        return h;
    }

    private static String normalizeMode(String m) {
        if (m == null) return "";
        String s = m.trim().toLowerCase();
        switch (s) {
            case "return": case "return_value": case "返回值": case "hook返回值":
                return MODE_RETURN;
            case "params": case "param": case "参数": case "hook参数值": case "参数替换":
                return MODE_PARAMS;
            case "intercept": case "block": case "拦截": case "拦截执行":
                return MODE_INTERCEPT;
            case "static": case "static_field": case "staticfield": case "静态": case "静态变量": case "hook静态变量":
                return MODE_STATIC;
            default:
                return "";
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    // ---------------- 值解析（与 SimpleHook 语义对齐） ----------------

    /** 解析用户输入的修改值：true/false、42、3.14、42L、3.14f、7b、12short、10086s、null、empty */
    public static Object parseValue(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        if ("empty".equalsIgnoreCase(s)) return EMPTY;
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        try {
            if (s.length() > 1 && (s.endsWith("L") || s.endsWith("l"))) {
                String n = s.substring(0, s.length() - 1);
                Long.parseLong(n);
                return Long.valueOf(n);
            }
            if (s.length() > 1 && s.endsWith("f")) {
                return Float.valueOf(s.substring(0, s.length() - 1));
            }
            if (s.length() > 1 && s.endsWith("d")) {
                return Double.valueOf(s.substring(0, s.length() - 1));
            }
            if (s.length() > 5 && s.toLowerCase().endsWith("short")) {
                return Short.valueOf(s.substring(0, s.length() - 5));
            }
            if (s.length() > 1 && s.endsWith("b")) {
                return Byte.valueOf(s.substring(0, s.length() - 1));
            }
            if (s.length() > 1 && s.endsWith("s") && s.matches("-?\\d+s")) {
                return s.substring(0, s.length() - 1);
            }
            if (s.matches("-?\\d+")) return Integer.valueOf(s);
            if (s.matches("-?\\d+\\.\\d+")) return Double.valueOf(s);
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** 把解析值转换为目标类型 */
    public static Object coerce(Object v, Class<?> t) {
        if (t == null || t == Object.class) return v;
        if (t == void.class) return null;
        if (v == EMPTY) return defaultValueFor(t);
        if (v == null) return null;
        if (t == boolean.class || t == Boolean.class) {
            if (v instanceof Boolean) return v;
            return Boolean.parseBoolean(String.valueOf(v));
        }
        if (t == char.class || t == Character.class) {
            String s = String.valueOf(v);
            return s.isEmpty() ? '\0' : s.charAt(0);
        }
        if (t == String.class) return v instanceof String ? v : String.valueOf(v);
        if (t == int.class || t == Integer.class) return asInt(v);
        if (t == long.class || t == Long.class) return asLong(v);
        if (t == float.class || t == Float.class) return asFloat(v);
        if (t == double.class || t == Double.class) return asDouble(v);
        if (t == short.class || t == Short.class) {
            Number n = (Number) v;
            return n.shortValue();
        }
        if (t == byte.class || t == Byte.class) {
            Number n = (Number) v;
            return n.byteValue();
        }
        return v;
    }

    public static Object defaultValueFor(Class<?> t) {
        if (t == null || t == void.class || t == Object.class) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return '\0';
        return null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v));
    }

    private static Long asLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(String.valueOf(v));
    }

    private static Float asFloat(Object v) {
        if (v instanceof Number) return ((Number) v).floatValue();
        return Float.parseFloat(String.valueOf(v));
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    /** 模式显示名 */
    public static String modeLabel(String mode) {
        if (MODE_PARAMS.equals(mode)) return "参数替换";
        if (MODE_INTERCEPT.equals(mode)) return "拦截执行";
        if (MODE_STATIC.equals(mode)) return "静态变量";
        return "返回固定值";
    }

    public static Map<String, List<HookEntry>> toMap(List<AppEntry> apps) {
        Map<String, List<HookEntry>> m = new HashMap<>();
        if (apps != null) {
            for (AppEntry a : apps) {
                if (a.enabled && a.pkg != null) m.put(a.pkg, a.hooks);
            }
        }
        return m;
    }
}
