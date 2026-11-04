package com.example.panesunlock;

import android.content.Context;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 格窗工作台 (io.github.yishisanren.panes) 解锁模块。
 *
 * 两条解锁路径（互为补充，确保万无一失）：
 *  1) 全局按 key 强制 android.os.BaseBundle 的读取（getBoolean / containsKey /
 *     getLong / getString），同时覆盖 App 进程与 SystemUI 宿主进程 —— 这是主机制，
 *     因为授权状态本身就是靠这些 Bundle 读取下发的。
 *  2) 在目标 App 进程内，生成合法的“永久试用令牌”并写入
 *     Settings.Global(panes_trial_v1) 与 Settings.Secure(sysui_tile_layout_rev)，
 *     让 App 自己的 yf.h()/a() 校验也能通过（HMAC 密钥硬编码在 App 内，可复现）。
 *
 * 注意：客户端本身没有“功能级”闸门，强制这些读取即可让 App 视为已解锁。
 */
public class PanesUnlockModule implements IXposedHookLoadPackage {

    private static final String TARGET = "io.github.yishisanren.panes";

    private static final String K_UNLOCKED = "io.github.yishisanren.panes.extra.LICENSE_UNLOCKED";
    private static final String K_TRIAL    = "io.github.yishisanren.panes.extra.LICENSE_TRIAL";
    private static final String K_MACHINE  = "io.github.yishisanren.panes.extra.LICENSE_MACHINE";
    private static final long   TRIAL_FOREVER = 4102444800000L; // 2100-01-01

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1) 主机制：强制 BaseBundle 读取（App + SystemUI 宿主）
        try {
            Class<?> baseBundle = XposedHelpers.findClass("android.os.BaseBundle", lpparam.classLoader);
            hookBundle(baseBundle);
        } catch (Throwable ignored) { /* 框架类必然存在 */ }

        // 2) 仅在目标 App 进程内：注入合法永久试用令牌（best-effort）
        if (TARGET.equals(lpparam.packageName)) {
            try {
                Context ctx = currentApplication();
                if (ctx != null) {
                    String token = TrialToken.generate();
                    Settings.Global.putString(ctx.getContentResolver(), "panes_trial_v1", token);
                    Settings.Secure.putString(ctx.getContentResolver(), "sysui_tile_layout_rev", token);
                }
            } catch (Throwable ignored) { /* 权限不足等情况下跳过，不影响主机制 */ }
        }
    }

    /** 反射获取当前 Application（避免依赖 Xposed 实现包的 AndroidAppHelper）。 */
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
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
        XposedHelpers.findAndHookMethod(baseBundle, "getString", String.class, String.class, strHook);
    }
}
