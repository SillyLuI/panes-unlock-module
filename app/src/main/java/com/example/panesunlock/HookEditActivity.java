package com.example.panesunlock;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个应用的拓展 hook 配置页（SimpleHook 式）：
 * 点 + → 选模式（11 种）→ 填类名/方法/参数/修改值/备忘（支持粘贴 smali 自动转换）。
 */
public class HookEditActivity extends AppCompatActivity {

    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_LABEL = "label";
    private static final String EXTRA_SMALI = "smali";

    private String pkg;
    private String label;
    private UserHookStore.AppEntry entry;
    private HooksAdapter adapter;

    public static Intent intent(Context ctx, String pkg, String label) {
        return intent(ctx, pkg, label, null);
    }

    public static Intent intent(Context ctx, String pkg, String label, String smali) {
        Intent i = new Intent(ctx, HookEditActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_LABEL, label);
        i.putExtra(EXTRA_SMALI, smali);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hook_edit);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        String smali = getIntent().getStringExtra(EXTRA_SMALI);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }

        TextView title = findViewById(R.id.he_title);
        TextView sub = findViewById(R.id.he_pkg);
        TextView avatar = findViewById(R.id.he_avatar);
        title.setText(label == null || label.isEmpty() ? pkg : label);
        sub.setText(pkg);
        avatar.setText(label == null || label.isEmpty() ? "?" : label.substring(0, 1));

        adapter = new HooksAdapter();
        RecyclerView list = findViewById(R.id.he_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.he_fab).setOnClickListener(v -> showModeChooser(-1));
        loadEntry();

        if (smali != null && !smali.trim().isEmpty()) {
            showModeChooser(-1, smali);
        }
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
        } else if (label != null && !label.isEmpty() && !label.equals(entry.label)) {
            entry.label = label;
            UserHookStore.save(this, apps);
        }
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
        adapter.reload();
    }

    // ---------------- 模式选择弹窗 ----------------

    private void showModeChooser(final int editIndex) {
        showModeChooser(editIndex, null);
    }

    private void showModeChooser(final int editIndex, final String smali) {
        View view = getLayoutInflater().inflate(R.layout.dialog_modes, null);
        GridLayout grid = view.findViewById(R.id.modes_grid);
        for (final String mode : UserHookStore.ALL_MODES) {
            Button b = new Button(this, null, 0);
            b.setText(UserHookStore.modeLabel(mode));
            b.setBackgroundResource(R.drawable.bg_search);
            b.setAllCaps(false);
            b.setTextColor(0xFF3F2D23);
            b.setPadding(dp(12), dp(14), dp(12), dp(14));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                modeChooserDlg.dismiss();
                showHookDialog(editIndex, mode, smali);
            });
            grid.addView(b);
        }
        modeChooserDlg = new AlertDialog.Builder(this)
                .setTitle("选择模式")
                .setView(view)
                .setNegativeButton("取消", null)
                .show();
    }

    private AlertDialog modeChooserDlg;

    // ---------------- 配置表单弹窗 ----------------

    private void showHookDialog(final int editIndex, final String mode, final String smali) {
        View view = getLayoutInflater().inflate(R.layout.dialog_hook, null);
        final TextView modeText = view.findViewById(R.id.dh_mode_text);
        final EditText etClass = view.findViewById(R.id.dh_class);
        final EditText etMethod = view.findViewById(R.id.dh_method);
        final EditText etParams = view.findViewById(R.id.dh_params);
        final EditText etValue = view.findViewById(R.id.dh_value);
        final EditText etField = view.findViewById(R.id.dh_field);
        final TextView fieldLabel = view.findViewById(R.id.dh_field_label);
        final EditText etMemo = view.findViewById(R.id.dh_memo);

        modeText.setText(UserHookStore.modeLabel(mode));
        modeText.setOnClickListener(v -> showModeChooser(editIndex, smali));
        updateFieldVisibility(mode, etField, fieldLabel);

        if (editIndex >= 0) {
            UserHookStore.HookEntry h = entry.hooks.get(editIndex);
            etClass.setText(h.className);
            etMethod.setText(h.methodName);
            etParams.setText(h.paramTypes);
            etValue.setText(h.value);
            etField.setText(h.fieldName);
            etMemo.setText(h.memo);
        } else if (smali != null && !smali.trim().isEmpty()) {
            applySmali(smali, etClass, etMethod, etParams, etValue);
        }

        view.findViewById(R.id.dh_smali).setOnClickListener(v -> {
            String text = clipboardText();
            if (text == null || text.trim().isEmpty()) {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!applySmali(text, etClass, etMethod, etParams, etValue)) {
                Toast.makeText(this, "解析失败：未找到 .method 行", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已从 smali 填充", Toast.LENGTH_SHORT).show();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? "编辑配置" : "添加配置")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    UserHookStore.HookEntry h = new UserHookStore.HookEntry();
                    h.mode = mode;
                    h.className = textOf(etClass);
                    h.methodName = textOf(etMethod);
                    h.paramTypes = textOf(etParams);
                    h.value = textOf(etValue);
                    h.fieldName = textOf(etField);
                    h.memo = textOf(etMemo);

                    if (h.className.isEmpty()) {
                        Toast.makeText(this, "类名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean logMode = UserHookStore.MODE_LOG_PARAMS.equals(h.mode)
                            || UserHookStore.MODE_LOG_RETURN.equals(h.mode)
                            || UserHookStore.MODE_LOG_BOTH.equals(h.mode)
                            || UserHookStore.MODE_LOG_STATIC.equals(h.mode)
                            || UserHookStore.MODE_LOG_INSTANCE.equals(h.mode);
                    if (h.methodName.isEmpty() && !UserHookStore.MODE_STATIC.equals(h.mode) && !logMode) {
                        Toast.makeText(this, "方法名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if ((UserHookStore.MODE_STATIC.equals(h.mode) || UserHookStore.MODE_INSTANCE.equals(h.mode))
                            && h.fieldName.isEmpty()) {
                        Toast.makeText(this, "该模式需要填写字段名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (UserHookStore.MODE_PARAMS.equals(h.mode) && h.paramTypes.isEmpty()) {
                        Toast.makeText(this, "参数替换模式需要填写参数类型", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (editIndex >= 0) {
                        entry.hooks.set(editIndex, h);
                    } else {
                        entry.hooks.add(h);
                    }
                    persist();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean applySmali(String text, EditText etClass, EditText etMethod,
                               EditText etParams, EditText etValue) {
        SmaliParser.Result r = SmaliParser.parse(text);
        if (!r.ok) return false;
        if (!r.className.isEmpty()) etClass.setText(r.className);
        etMethod.setText(r.methodName);
        etParams.setText(r.paramTypes);
        if (!r.guessedValue.isEmpty()) etValue.setText(r.guessedValue);
        return true;
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

    private void updateFieldVisibility(String mode, EditText etField, TextView label) {
        boolean show = UserHookStore.MODE_STATIC.equals(mode) || UserHookStore.MODE_INSTANCE.equals(mode)
                || UserHookStore.MODE_LOG_STATIC.equals(mode) || UserHookStore.MODE_LOG_INSTANCE.equals(mode);
        etField.setVisibility(show ? View.VISIBLE : View.GONE);
        label.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static String textOf(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 列表适配器 ----------------

    private class HooksAdapter extends RecyclerView.Adapter<HooksAdapter.VH> {
        private final List<UserHookStore.HookEntry> data = new ArrayList<>();

        void reload() {
            data.clear();
            data.addAll(entry.hooks);
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
            h.mode.setText(UserHookStore.modeLabel(e.mode));
            h.cls.setText(e.className);
            StringBuilder sb = new StringBuilder();
            if (!e.methodName.isEmpty()) sb.append(e.methodName);
            if (!e.paramTypes.isEmpty()) sb.append("(").append(e.paramTypes).append(")");
            if (!e.value.isEmpty()) sb.append("  →  ").append(e.value);
            if (!e.fieldName.isEmpty()) sb.append("  字段: ").append(e.fieldName);
            h.detail.setText(sb.length() == 0 ? "（点击编辑）" : sb.toString());
            h.del.setOnClickListener(v -> {
                entry.hooks.remove(pos);
                persist();
            });
            h.itemView.setOnClickListener(v ->
                    showHookDialog(pos, e.mode, null));
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
