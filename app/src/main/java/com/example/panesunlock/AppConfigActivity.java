package com.example.panesunlock;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.List;

/**
 * 「配置：应用」页（图 3）：应用级开关 + 右上角齿轮进入 Hook 配置页。
 */
public class AppConfigActivity extends AppCompatActivity {

    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_LABEL = "label";

    private String pkg;
    private String label;
    private UserHookStore.AppEntry entry;
    private List<UserHookStore.AppEntry> apps;
    private boolean firstResume = true;

    public static Intent intent(Context ctx, String pkg, String label) {
        Intent i = new Intent(ctx, AppConfigActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_LABEL, label);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_config);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.ac_title)).setText(
                "配置：" + (label == null || label.isEmpty() ? pkg : label));
        findViewById(R.id.ac_back).setOnClickListener(v -> finish());
        findViewById(R.id.ac_hooks).setOnClickListener(v ->
                startActivity(HookEditActivity.intent(this, pkg, label)));
        findViewById(R.id.ac_save).setOnClickListener(v -> save());

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「Hook 配置」页返回后重新读取，避免保存时覆盖新增的 Hook
        if (firstResume) {
            firstResume = false;
        } else {
            load();
        }
    }

    private void load() {
        apps = UserHookStore.load(this);
        entry = null;
        for (UserHookStore.AppEntry a : apps) {
            if (a.pkg.equals(pkg)) {
                entry = a;
                break;
            }
        }
        if (entry == null) {
            entry = new UserHookStore.AppEntry();
            entry.pkg = pkg;
            entry.label = label == null ? pkg : label;
            apps.add(entry);
            UserHookStore.save(this, apps);
        }
        buildUi();
    }

    private void buildUi() {
        LinearLayout basic = findViewById(R.id.ac_group_basic);
        LinearLayout detect = findViewById(R.id.ac_group_detect);
        LinearLayout network = findViewById(R.id.ac_group_network);
        basic.removeAllViews();
        detect.removeAllViews();
        network.removeAllViews();

        addSwitch(basic, "总开关", "包名：" + pkg, entry.enabled, c -> entry.enabled = c);
        addSwitch(basic, "Toast 提示", "启动被 Hook 的应用时，显示 Toast 提示", entry.toast,
                c -> entry.toast = c);

        addSwitch(detect, "禁用无障碍服务检测", "隐藏已开启的无障碍服务",
                entry.blockAccessibility, c -> entry.blockAccessibility = c);
        addSwitch(detect, "禁用开发者选项检测", "使开发者选项状态显示为关闭",
                entry.blockDevOptions, c -> entry.blockDevOptions = c);
        addSwitch(detect, "禁用 USB 调试检测", "使 USB 调试状态显示为关闭",
                entry.blockUsbDebug, c -> entry.blockUsbDebug = c);
        addSwitch(detect, "禁用虚拟机检测", "绕过模拟器 / 云手机特征识别",
                entry.blockVm, c -> entry.blockVm = c);
        addSwitch(detect, "禁用部分环境检测", "绕过部分 Root、Xposed 环境检测",
                entry.blockEnv, c -> entry.blockEnv = c);

        addSwitch(network, "隐藏 VPN", "绕过 VPN / 系统代理检测",
                entry.hideVpn, c -> entry.hideVpn = c);
        addSwitch(network, "加速器代理白名单", "让加速器为 APP 提供 VPN 代理功能",
                entry.hideProxy, c -> entry.hideProxy = c);
    }

    private void addSwitch(LinearLayout group, String title, String sub,
                           boolean checked, OnToggle listener) {
        View row = getLayoutInflater().inflate(R.layout.item_switch, group, false);
        ((TextView) row.findViewById(R.id.sw_title)).setText(title);
        TextView subView = row.findViewById(R.id.sw_sub);
        if (sub == null || sub.isEmpty()) {
            subView.setVisibility(View.GONE);
        } else {
            subView.setText(sub);
        }
        SwitchCompat sw = row.findViewById(R.id.sw_switch);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, c) -> listener.onToggle(c));
        row.setOnClickListener(v -> sw.toggle());
        if (group.getChildCount() > 0) {
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.bg_card_stroke));
            group.addView(divider);
        }
        group.addView(row);
    }

    private void save() {
        apps = UserHookStore.load(this);
        boolean found = false;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).pkg.equals(pkg)) {
                apps.set(i, entry);
                found = true;
                break;
            }
        }
        if (!found) apps.add(entry);
        UserHookStore.save(this, apps);
        Toast.makeText(this, "已保存，重启目标应用后生效", Toast.LENGTH_LONG).show();
        finish();
    }

    private interface OnToggle {
        void onToggle(boolean checked);
    }
}
