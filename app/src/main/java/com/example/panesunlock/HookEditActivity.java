package com.example.panesunlock;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Hook 配置」页（图 4）：该应用的总开关、Hook 列表，以及导入 / 新建入口。
 */
public class HookEditActivity extends AppCompatActivity {

    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_LABEL = "label";

    private String pkg;
    private String label;
    private UserHookStore.AppEntry entry;
    private HooksAdapter adapter;
    private SwitchCompat master;

    public static Intent intent(Context ctx, String pkg, String label) {
        Intent i = new Intent(ctx, HookEditActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_LABEL, label);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hook_edit);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }

        findViewById(R.id.he_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.he_pkg)).setText("包名：" + pkg);
        findViewById(R.id.he_more).setOnClickListener(v -> showMore());
        findViewById(R.id.he_new).setOnClickListener(v ->
                startActivity(HookAddActivity.intent(this, pkg)));
        findViewById(R.id.he_import).setOnClickListener(v -> importFromClipboard());

        master = findViewById(R.id.he_master);
        master.setOnCheckedChangeListener((b, c) -> {
            if (entry == null) return;
            entry.enabled = c;
            persist();
            Toast.makeText(this, c ? "已启用，重启目标应用后生效" : "已停用", Toast.LENGTH_SHORT).show();
        });

        adapter = new HooksAdapter();
        RecyclerView list = findViewById(R.id.he_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEntry();
    }

    private void loadEntry() {
        List<UserHookStore.AppEntry> apps = UserHookStore.load(this);
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
        master.setOnCheckedChangeListener(null);
        master.setChecked(entry.enabled);
        master.setOnCheckedChangeListener((b, c) -> {
            entry.enabled = c;
            persist();
            Toast.makeText(this, c ? "已启用，重启目标应用后生效" : "已停用", Toast.LENGTH_SHORT).show();
        });
        adapter.reload();
    }

    private void persist() {
        List<UserHookStore.AppEntry> apps = UserHookStore.load(this);
        UserHookStore.AppEntry exist = null;
        for (UserHookStore.AppEntry a : apps) {
            if (a.pkg.equals(pkg)) {
                exist = a;
                break;
            }
        }
        if (exist == null) {
            apps.add(entry);
        } else {
            exist.label = entry.label;
            exist.enabled = entry.enabled;
            exist.hooks = entry.hooks;
        }
        UserHookStore.save(this, apps);
    }

    private void showMore() {
        new AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(new String[]{"配置 LSPosed 作用域", "删除该应用配置"}, (d, which) -> {
                    if (which == 0) {
                        LSPosedHelper.openScope(this, pkg);
                    } else {
                        confirmDelete();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + pkg + " 的配置？")
                .setMessage("将移除该应用的所有自定义 Hook。")
                .setPositiveButton("删除", (d, w) -> {
                    List<UserHookStore.AppEntry> apps = UserHookStore.load(this);
                    for (int i = apps.size() - 1; i >= 0; i--) {
                        if (apps.get(i).pkg.equals(pkg)) apps.remove(i);
                    }
                    UserHookStore.save(this, apps);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导入：剪贴板是 JSON → 合并到本应用；是 smali → 打开新增页预填 */
    private void importFromClipboard() {
        String text = clipboardText();
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            List<UserHookStore.AppEntry> imported = UserHookStore.parseConfig(t);
            int n = 0;
            for (UserHookStore.AppEntry a : imported) {
                if (!a.hooks.isEmpty()) {
                    entry.hooks.addAll(a.hooks);
                    n += a.hooks.size();
                }
            }
            if (n == 0) {
                Toast.makeText(this, "未找到可用的 Hook 配置", Toast.LENGTH_SHORT).show();
                return;
            }
            persist();
            adapter.reload();
            Toast.makeText(this, "已导入 " + n + " 条 Hook", Toast.LENGTH_SHORT).show();
            return;
        }
        SmaliParser.Result r = SmaliParser.parse(t);
        if (r.ok) {
            ClipboardHolder.smali = t;
            startActivity(HookAddActivity.intent(this, pkg));
            return;
        }
        Toast.makeText(this, "无法识别（需要 JSON 配置或 smali 片段）", Toast.LENGTH_LONG).show();
    }

    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemAt(0) == null) {
                return null;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供新增页读取一次性的 smali 文本 */
    public static class ClipboardHolder {
        public static String smali;
    }

    private class HooksAdapter extends RecyclerView.Adapter<HooksAdapter.VH> {
        private final List<UserHookStore.HookEntry> data = new ArrayList<>();

        void reload() {
            data.clear();
            if (entry != null) data.addAll(entry.hooks);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(HookEditActivity.this)
                    .inflate(R.layout.item_hook, parent, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final UserHookStore.HookEntry e = data.get(pos);
            h.memo.setText(e.memo == null || e.memo.isEmpty()
                    ? (e.className.replace('.', '/') + "." + e.methodName)
                    : e.memo);
            h.mode.setText(UserHookStore.modeLabel(e.mode) + (e.enabled ? "" : "（已停用）"));
            h.cls.setText(e.className);
            StringBuilder sb = new StringBuilder();
            if (!e.methodName.isEmpty()) sb.append(e.methodName);
            if (!e.paramTypes.isEmpty()) sb.append("(").append(e.paramTypes).append(")");
            if (!e.value.isEmpty()) sb.append("  →  ").append(e.value);
            if (!e.returnType.isEmpty()) sb.append("  : ").append(e.returnType);
            h.detail.setText(sb.length() == 0 ? "（点击编辑）" : sb.toString());
            h.del.setOnClickListener(v -> {
                int idx = entry.hooks.indexOf(e);
                if (idx >= 0) {
                    entry.hooks.remove(idx);
                    persist();
                    reload();
                }
            });
            h.itemView.setOnClickListener(v -> {
                int idx = entry.hooks.indexOf(e);
                if (idx >= 0) startActivity(HookAddActivity.intent(HookEditActivity.this, pkg, idx));
            });
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(HookEditActivity.this)
                        .setTitle("Hook 操作")
                        .setItems(new String[]{e.enabled ? "停用" : "启用", "删除"}, (d, w) -> {
                            if (w == 0) {
                                e.enabled = !e.enabled;
                            } else {
                                entry.hooks.remove(e);
                            }
                            persist();
                            reload();
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
            TextView memo, mode, cls, detail, del;

            VH(View v) {
                super(v);
                memo = v.findViewById(R.id.hook_memo);
                mode = v.findViewById(R.id.hook_mode);
                cls = v.findViewById(R.id.hook_class);
                detail = v.findViewById(R.id.hook_detail);
                del = v.findViewById(R.id.hook_delete);
            }
        }
    }
}
