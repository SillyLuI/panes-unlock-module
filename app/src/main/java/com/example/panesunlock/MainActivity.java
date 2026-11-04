package com.example.panesunlock;

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 模块主界面：首页 / 适配应用 / 拓展（SimpleHook 式自定义 hook）/ 设置。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_IMPORT = 11;
    private static final int REQ_EXPORT = 12;
    private static final String GITHUB_URL = "https://github.com/SillyLuI/panes-unlock-module";

    private List<HookConf.AppEntry> builtinApps = new ArrayList<>();
    private List<UserHookStore.AppEntry> extApps = new ArrayList<>();

    private AppsAdapter appsAdapter;
    private ExtAdapter extAdapter;

    private int current = -1;
    private View pageHome, pageApps, pageExt, pageSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyAppearance();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        builtinApps = HookConf.parse(readHooksJson());
        extApps = UserHookStore.load(this);

        bindNav();
        setupApps();
        showPage(0);
    }

    private String readHooksJson() {
        try {
            InputStream is = getAssets().open("hooks.json");
            String s = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
            is.close();
            return s;
        } catch (Throwable t) {
            return HookConf.DEFAULT_JSON;
        }
    }

    private void applyAppearance() {
        int mode = getSharedPreferences("ui", MODE_PRIVATE).getInt("appearance", 0);
        AppCompatDelegate.setDefaultNightMode(mode == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    // ---------------- 底部导航 ----------------

    private void bindNav() {
        int[] ids = {R.id.nav_home, R.id.nav_apps, R.id.nav_ext, R.id.nav_settings};
        View.OnClickListener l = v -> {
            int id = v.getId();
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) {
                    showPage(i);
                    break;
                }
            }
        };
        for (int id : ids) findViewById(id).setOnClickListener(l);
    }

    private void showPage(int index) {
        if (current == index) return;
        current = index;
        ViewGroup container = findViewById(R.id.page_container);
        container.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        if (index == 0) {
            pageHome = inf.inflate(R.layout.page_home, container, false);
            container.addView(pageHome);
            fillHome();
        } else if (index == 1) {
            pageApps = inf.inflate(R.layout.page_apps, container, false);
            container.addView(pageApps);
            bindAppsPage();
        } else if (index == 2) {
            pageExt = inf.inflate(R.layout.page_ext, container, false);
            container.addView(pageExt);
            bindExtPage();
        } else {
            pageSettings = inf.inflate(R.layout.page_settings, container, false);
            container.addView(pageSettings);
            bindSettingsPage();
        }
        markNavSelected(index);
    }

    private void markNavSelected(int index) {
        int[] navs = {R.id.nav_home, R.id.nav_apps, R.id.nav_ext, R.id.nav_settings};
        for (int i = 0; i < navs.length; i++) {
            View item = findViewById(navs[i]);
            item.setBackgroundResource(i == index ? R.drawable.bg_pill : 0);
            int color = androidx.core.content.ContextCompat.getColor(this,
                    i == index ? R.color.accent_deep : R.color.text_secondary);
            LinearLayout group = (LinearLayout) item;
            for (int c = 0; c < group.getChildCount(); c++) {
                View child = group.getChildAt(c);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(color);
                } else if (child instanceof android.widget.ImageView) {
                    ((android.widget.ImageView) child).setColorFilter(color);
                }
            }
        }
    }

    // ---------------- 首页 ----------------

    private void fillHome() {
        if (pageHome == null) return;
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) pageHome.findViewById(R.id.home_version)).setText(ver + " (build "
                    + getPackageManager().getPackageInfo(getPackageName(), 0).versionCode + ")");
        } catch (Throwable ignored) {
        }
        ((TextView) pageHome.findViewById(R.id.home_stat_apps)).setText(String.valueOf(supportedCount()));
        ((TextView) pageHome.findViewById(R.id.home_stat_ext)).setText(String.valueOf(extApps.size()));
        ((TextView) pageHome.findViewById(R.id.home_build)).setText(
                "v" + safeVersion() + " · 配置驱动（hooks.json）");
        ((TextView) pageHome.findViewById(R.id.home_android)).setText(
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        ((TextView) pageHome.findViewById(R.id.home_device)).setText(Build.MANUFACTURER + " " + Build.MODEL);
        ((TextView) pageHome.findViewById(R.id.home_arch)).setText(
                Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        pageHome.findViewById(R.id.home_github).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)));
            } catch (Throwable ignored) {
            }
        });
    }

    private String safeVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int supportedCount() {
        int n = 0;
        for (HookConf.AppEntry a : builtinApps) {
            if (a.rules != null && !a.rules.isEmpty()) n++;
        }
        return n;
    }

    // ---------------- 应用页 ----------------

    private void setupApps() {
        appsAdapter = new AppsAdapter();
    }

    private void bindAppsPage() {
        EditText search = pageApps.findViewById(R.id.apps_search);
        RecyclerView list = pageApps.findViewById(R.id.apps_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(appsAdapter);
        appsAdapter.refilter(textOf(search));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                appsAdapter.refilter(s.toString());
            }
        });
    }

    private static String textOf(EditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    private class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.VH> {
        private final List<HookConf.AppEntry> data = new ArrayList<>();
        private String query = "";

        void refilter(String q) {
            query = q == null ? "" : q.toLowerCase();
            data.clear();
            for (HookConf.AppEntry a : builtinApps) {
                if (query.isEmpty()
                        || a.name.toLowerCase().contains(query)
                        || a.pkg.toLowerCase().contains(query)) {
                    data.add(a);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final HookConf.AppEntry a = data.get(pos);
            h.avatar.setText(a.name == null || a.name.isEmpty() ? "?" : a.name.substring(0, 1));
            h.title.setText(a.name);
            h.pkg.setText(a.pkg);
            boolean supported = a.rules != null && !a.rules.isEmpty();
            h.note.setText(supported ? (a.note == null || a.note.isEmpty() ? "已支持" : a.note) : "待补 hook");
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(UserHookStore.isBuiltinEnabled(MainActivity.this, a.pkg, true));
            h.check.setOnCheckedChangeListener((b, checked) ->
                    UserHookStore.setBuiltinEnabled(MainActivity.this, a.pkg, checked));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView avatar, title, pkg, note;
            android.widget.CheckBox check;

            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.item_avatar);
                title = v.findViewById(R.id.item_title);
                pkg = v.findViewById(R.id.item_pkg);
                note = v.findViewById(R.id.item_note);
                check = v.findViewById(R.id.item_check);
            }
        }
    }

    // ---------------- 拓展页 ----------------

    private void bindExtPage() {
        EditText search = pageExt.findViewById(R.id.ext_search);
        RecyclerView list = pageExt.findViewById(R.id.ext_list);
        View empty = pageExt.findViewById(R.id.ext_empty);
        extAdapter = new ExtAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(extAdapter);
        extAdapter.refilter(textOf(search));
        empty.setVisibility(extAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        list.setVisibility(extAdapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                extAdapter.refilter(s.toString());
                empty.setVisibility(extAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                list.setVisibility(extAdapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
            }
        });

        final View fab = pageExt.findViewById(R.id.fab_add);
        final View menu = pageExt.findViewById(R.id.ext_fab_menu);
        final android.widget.ImageView fabIcon = pageExt.findViewById(R.id.ext_fab_icon);
        fab.setOnClickListener(v -> {
            boolean open = menu.getVisibility() == View.VISIBLE;
            menu.setVisibility(open ? View.GONE : View.VISIBLE);
            fabIcon.setImageResource(open ? R.drawable.ic_add : R.drawable.ic_close);
        });
        pageExt.findViewById(R.id.fab_addcfg).setOnClickListener(v -> {
            collapseFabMenu();
            showAppPicker();
        });
        pageExt.findViewById(R.id.fab_import).setOnClickListener(v -> {
            collapseFabMenu();
            importFromClipboard();
        });
        pageExt.findViewById(R.id.fab_net).setOnClickListener(v -> {
            collapseFabMenu();
            importFromNetwork();
        });
        pageExt.findViewById(R.id.fab_share).setOnClickListener(v -> {
            collapseFabMenu();
            shareConfig();
        });
    }

    private void collapseFabMenu() {
        if (pageExt != null) {
            pageExt.findViewById(R.id.ext_fab_menu).setVisibility(View.GONE);
            ((android.widget.ImageView) pageExt.findViewById(R.id.ext_fab_icon))
                    .setImageResource(R.drawable.ic_add);
        }
    }

    private void refreshExt() {
        if (pageExt != null) {
            extAdapter.refilter(textOf((EditText) pageExt.findViewById(R.id.ext_search)));
            pageExt.findViewById(R.id.ext_empty).setVisibility(
                    extAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            pageExt.findViewById(R.id.ext_list).setVisibility(
                    extAdapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Map<String, String> appsMap = new LinkedHashMap<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (!appsMap.containsKey(pkg)) {
                appsMap.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_pick_app, null);
        RecyclerView list = view.findViewById(R.id.pick_list);
        EditText search = view.findViewById(R.id.pick_search);
        List<String> pkgs = new ArrayList<>(appsMap.keySet());
        PickerAdapter adapter = new PickerAdapter(appsMap, pkgs);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                adapter.refilter(s.toString());
            }
        });

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择目标应用")
                .setView(view)
                .setNegativeButton("取消", null)
                .create();
        adapter.setOnPick((pkg, label) -> {
            dlg.dismiss();
            openExtEditor(pkg, label);
        });
        dlg.show();
    }

    private void openExtEditor(String pkg, String label) {
        String smali = pendingSmali;
        pendingSmali = null;
        // 已存在则直接编辑
        for (UserHookStore.AppEntry a : extApps) {
            if (a.pkg.equals(pkg)) {
                startActivity(HookEditActivity.intent(this, pkg, a.label, smali));
                return;
            }
        }
        startActivity(HookEditActivity.intent(this, pkg, label, smali));
    }

    // ---------------- 剪贴板 / 网络 / 分享 ----------------

    private String pendingSmali;

    private String readClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemAt(0) == null) return null;
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 导入配置：剪贴板里是 JSON → 直接导入；是 smali → 选目标应用后预填新 hook */
    private void importFromClipboard() {
        String text = readClipboard();
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            int n = importConfig(t);
            if (n > 0) {
                Toast.makeText(this, "从剪贴板导入 " + n + " 条 hook", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "导入失败：JSON 里没有可用的 hook", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (t.contains(".method") || t.contains(".class")) {
            SmaliParser.Result r = SmaliParser.parse(t);
            if (!r.ok) {
                Toast.makeText(this, "smali 解析失败：" + r.error, Toast.LENGTH_SHORT).show();
                return;
            }
            pendingSmali = t;
            Toast.makeText(this, "识别: " + (r.className.isEmpty() ? r.methodName : r.className + "." + r.methodName)
                    + "，请选择目标应用", Toast.LENGTH_LONG).show();
            showAppPicker();
            return;
        }
        Toast.makeText(this, "剪贴板内容无法识别（需要 JSON 配置或 smali 片段）", Toast.LENGTH_LONG).show();
    }

    /** 从网络导入：输入 URL，下载 JSON 后合并 */
    private void importFromNetwork() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setHint("https://example.com/config.json");
        et.setPadding(dp16(), dp16(), dp16(), dp16());
        new AlertDialog.Builder(this)
                .setTitle("从网络导入配置")
                .setView(et)
                .setPositiveButton("下载", (d, w) -> {
                    String url = et.getText() == null ? "" : et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    Toast.makeText(this, "下载中…", Toast.LENGTH_SHORT).show();
                    final String u = url;
                    new Thread(() -> {
                        String result = fetchUrl(u);
                        runOnUiThread(() -> {
                            if (result == null) {
                                Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show();
                            } else {
                                int n = importConfig(result);
                                Toast.makeText(this, n > 0 ? "导入 " + n + " 条 hook"
                                        : "导入失败：无可用 hook", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp16() {
        return (int) (16 * getResources().getDisplayMetrics().density);
    }

    private static String fetchUrl(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (java.io.InputStream is = c.getInputStream()) {
                return new java.util.Scanner(is, "UTF-8").useDelimiter("\\A").next();
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 分享配置：复制到剪贴板 + 系统分享面板 */
    private void shareConfig() {
        String json = UserHookStore.toJson(extApps);
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("panes_hooks", json));
        } catch (Throwable ignored) {
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, json);
        startActivity(Intent.createChooser(send, "分享配置（已同时复制到剪贴板）"));
    }

    private interface OnPick {
        void pick(String pkg, String label);
    }

    private class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {
        private final Map<String, String> all;
        private final List<String> shown = new ArrayList<>();
        private OnPick onPick;

        PickerAdapter(Map<String, String> all, List<String> initial) {
            this.all = all;
            shown.addAll(initial);
        }

        void setOnPick(OnPick l) {
            onPick = l;
        }

        void refilter(String q) {
            String query = q == null ? "" : q.toLowerCase();
            shown.clear();
            for (Map.Entry<String, String> e : all.entrySet()) {
                if (query.isEmpty()
                        || e.getKey().toLowerCase().contains(query)
                        || e.getValue().toLowerCase().contains(query)) {
                    shown.add(e.getKey());
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_ext, parent, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final String pkg = shown.get(pos);
            final String label = all.get(pkg);
            h.avatar.setText(label == null || label.isEmpty() ? "?" : label.substring(0, 1));
            h.title.setText(label);
            h.pkg.setText(pkg);
            h.count.setText("");
            h.itemView.setOnClickListener(v -> {
                if (onPick != null) onPick.pick(pkg, label);
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView avatar, title, pkg, count;

            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.item_avatar);
                title = v.findViewById(R.id.item_title);
                pkg = v.findViewById(R.id.item_pkg);
                count = v.findViewById(R.id.item_count);
            }
        }
    }

    private class ExtAdapter extends RecyclerView.Adapter<ExtAdapter.VH> {
        private final List<UserHookStore.AppEntry> data = new ArrayList<>();
        private String query = "";

        void refilter(String q) {
            query = q == null ? "" : q.toLowerCase();
            data.clear();
            for (UserHookStore.AppEntry a : extApps) {
                if (query.isEmpty()
                        || a.pkg.toLowerCase().contains(query)
                        || a.label.toLowerCase().contains(query)) {
                    data.add(a);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(MainActivity.this)
                    .inflate(R.layout.item_ext, parent, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final UserHookStore.AppEntry a = data.get(pos);
            h.avatar.setText(a.label == null || a.label.isEmpty() ? "?" : a.label.substring(0, 1));
            h.title.setText(a.label);
            h.pkg.setText(a.pkg);
            h.count.setText(a.hooks.size() + " 条规则");
            h.itemView.setOnClickListener(v ->
                    startActivity(HookEditActivity.intent(MainActivity.this, a.pkg, a.label)));
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("移除 " + a.label + " 的拓展配置？")
                        .setPositiveButton("移除", (d, w) -> {
                            extApps.remove(a);
                            UserHookStore.save(MainActivity.this, extApps);
                            refreshExt();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView avatar, title, pkg, count;

            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.item_avatar);
                title = v.findViewById(R.id.item_title);
                pkg = v.findViewById(R.id.item_pkg);
                count = v.findViewById(R.id.item_count);
            }
        }
    }

    // ---------------- 设置页 ----------------

    private void bindSettingsPage() {
        pageSettings.findViewById(R.id.set_appearance).setOnClickListener(v -> showAppearanceDialog());

        android.widget.Switch blur = pageSettings.findViewById(R.id.set_blur);
        blur.setChecked(getSharedPreferences("ui", MODE_PRIVATE).getBoolean("blur", false));
        blur.setOnCheckedChangeListener((b, c) ->
                getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("blur", c).apply());

        android.widget.Switch icon = pageSettings.findViewById(R.id.set_launcher_icon);
        icon.setChecked(isLauncherEnabled());
        icon.setOnCheckedChangeListener((b, c) -> setLauncherEnabled(c));

        android.widget.Switch auto = pageSettings.findViewById(R.id.set_auto_update);
        auto.setChecked(getSharedPreferences("ui", MODE_PRIVATE).getBoolean("auto_update", true));
        auto.setOnCheckedChangeListener((b, c) ->
                getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("auto_update", c).apply());

        pageSettings.findViewById(R.id.set_import).setOnClickListener(v -> startImport());
        pageSettings.findViewById(R.id.set_export).setOnClickListener(v -> startExport());
        pageSettings.findViewById(R.id.set_clear).setOnClickListener(v -> confirmClearExt());
    }

    private void showAppearanceDialog() {
        final String[] items = {"跟随系统", "亮色", "暗色"};
        int cur = getSharedPreferences("ui", MODE_PRIVATE).getInt("appearance", 0);
        new AlertDialog.Builder(this)
                .setTitle("外观")
                .setSingleChoiceItems(items, cur, (DialogInterface d, int which) -> {
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("appearance", which).apply();
                    AppCompatDelegate.setDefaultNightMode(which == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                            : which == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                            : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isLauncherEnabled() {
        ComponentName cn = new ComponentName(this, "com.example.panesunlock.LauncherAlias");
        return getPackageManager().getComponentEnabledSetting(cn)
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setLauncherEnabled(boolean enabled) {
        try {
            ComponentName cn = new ComponentName(this, "com.example.panesunlock.LauncherAlias");
            getPackageManager().setComponentEnabledSetting(cn,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Toast.makeText(this, "切换失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void startExport() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "panes_hooks.json");
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                String text = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
                is.close();
                int n = importConfig(text);
                Toast.makeText(this, "导入 " + n + " 条 hook", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "导入失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_EXPORT) {
            try {
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(UserHookStore.toJson(extApps).getBytes("UTF-8"));
                os.close();
                Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 合并导入（兼容本模块格式与 SimpleHook 常见格式） */
    private int importConfig(String json) {
        List<UserHookStore.AppEntry> imported = UserHookStore.parseConfig(json);
        int count = 0;
        for (UserHookStore.AppEntry na : imported) {
            UserHookStore.AppEntry exist = null;
            for (UserHookStore.AppEntry a : extApps) {
                if (a.pkg.equals(na.pkg)) {
                    exist = a;
                    break;
                }
            }
            if (exist == null) {
                extApps.add(na);
            } else {
                exist.hooks.addAll(na.hooks);
            }
            count += na.hooks.size();
        }
        UserHookStore.save(this, extApps);
        refreshExt();
        return count;
    }

    private void confirmClearExt() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部拓展配置？")
                .setMessage("将删除所有用户自定义 hook，且不可恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    extApps.clear();
                    UserHookStore.save(this, extApps);
                    refreshExt();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        extApps = UserHookStore.load(this);
        refreshExt();
    }
}
