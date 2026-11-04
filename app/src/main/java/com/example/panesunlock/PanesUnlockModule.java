package com.example.panesunlock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 格窗工作台 (io.github.yishisanren.panes) 解锁模块。
 *
 * 原理：授权状态（LICENSE_UNLOCKED / LICENSE_TRIAL）由宿主 / SystemUI 通过
 * android.os.BaseBundle 的带参读取（getBoolean / containsKey / getLong）下发给客户端。
 * SimpleHook 只能 hook 无参方法，够不到这些带参读取，因此改用 LSPosed 在运行时
 * 按 key 条件强制返回值。
 *
 * 注意：本 App 客户端本身没有“功能级”闸门（yf.c()/a() 为死代码），
 * 因此强制这些 Bundle 读取即可让客户端视为已解锁 / 试用永不过期；
 * 若宿主侧另有独立校验（读 SP / 签名），本模块再叠加对 yf.* 的强制。
 */
public class PanesUnlockModule implements IXposedHookLoadPackage {

    private static final String TARGET = "io.github.yishisanren.panes";

    private static final String K_UNLOCKED = "io.github.yishisanren.panes.extra.LICENSE_UNLOCKED";
    private static final String K_TRIAL    = "io.github.yishisanren.panes.extra.LICENSE_TRIAL";
    private static final String K_MACHINE  = "io.github.yishisanren.panes.extra.LICENSE_MACHINE";
    private static final long   TRIAL_FOREVER = 4102444800000L; // 2100-01-01

    private static boolean baseHooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1) 全局（按 key 条件）拦截 BaseBundle 读取 —— 同时覆盖 App 与 SystemUI 宿主进程
        if (!baseHooked) {
            baseHooked = true;
            try {
                Class<?> baseBundle = XposedHelpers.findClass("android.os.BaseBundle", lpparam.classLoader);
                hookBundle(baseBundle);
            } catch (Throwable ignored) { /* 框架类一定存在，理论上不会失败 */ }
        }

        // 2) 仅 App 进程：顺手把授权类 yf 的判定也顶成 true（防御性，死代码也无妨）
        if (TARGET.equals(lpparam.packageName)) {
            forceTrue(lpparam.classLoader, "yf", "c");
            forceTrue(lpparam.classLoader, "yf", "a");
            forceTrue(lpparam.classLoader, "yf", "f");
        }
    }

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
    }

    /** 把指定无参布尔方法（若存在）的返回值强制为 true。 */
    private void forceTrue(ClassLoader cl, String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getResult() instanceof Boolean) {
                        p.setResult(true);
                    }
                }
            });
        } catch (Throwable ignored) { /* 该类/方法在此版本不存在则跳过 */ }
    }
}
