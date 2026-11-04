package com.example.panesunlock;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

/**
 * 打开 LSPosed 管理器，定位到本模块的作用域选择页。
 * 模块 Hook 只注入到 LSPosed 作用域内勾选的应用，新增目标应用后必须勾选。
 */
public final class LSPosedHelper {

    private static final String MANAGER = "org.lsposed.manager";

    /** 作用域选择页（LSPosed Manager 常见入口） */
    private static final String[] SCOPE_ACTIVITIES = {
            "org.lsposed.manager.ui.activity.AppListActivity",
            "org.lsposed.manager.ui.activity.MainActivity"
    };

    private LSPosedHelper() {
    }

    /** 打开本模块的作用域选择页；appPkg 非空时尝试预选该应用 */
    public static void openScope(Context ctx, String appPkg) {
        for (String act : SCOPE_ACTIVITIES) {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(new ComponentName(MANAGER, act));
                intent.putExtra("module_package", ctx.getPackageName());
                intent.putExtra("module_name", "Panes Unlock");
                if (appPkg != null && !appPkg.isEmpty()) {
                    intent.putExtra("app", appPkg);
                    intent.putExtra("package_name", appPkg);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(MANAGER);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 实在打不开：把模块包名复制到剪贴板，方便在 LSPosed 里搜索
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("module", ctx.getPackageName()));
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(ctx,
                "无法直接打开 LSPosed，已复制模块包名。请手动在 LSPosed → 模块 → Panes Unlock → 作用域中勾选目标应用",
                Toast.LENGTH_LONG).show();
    }
}
