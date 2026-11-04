package com.example.panesunlock;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个应用的拓展 hook 编辑器（SimpleHook 式自填配置，保存后目标应用重启即生效，无需重编模块）。
 */
public class HookEditActivity extends AppCompatActivity {

    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_LABEL = "label";

    private static final String[] MODE_LABELS = {"返回固定值", "参数替换", "拦截执行", "静态变量"};

    private String pkg;
    private String label;
    private UserHookStore.AppEntry entry;
    private HooksAdapter adapter;

    public static Intent intent(Context ctx, String pkg, String label) {
        Intent i = new Intent(ctx, HookEditActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_LABEL, label);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.bg_page);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF3F2D23);
        title.setText(label == null || label.isEmpty() ? pkg : label);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setTextSize(13);
        sub.setTextColor(0xFF9C887A);
        sub.setPadding(0, dp(6), 0, dp(4));
        sub.setText(pkg);
        root.addView(sub);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(0xFF9C887A);
        hint.setPadding(0, dp(4), 0, dp(10));
        hint.setText("新增/修改后，请确认 LSPosed 已勾选该应用作用域，然后强制停止或重启目标应用生效。");
        root.addView(hint);

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new HooksAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View fab = new View(this);
        fab.setBackgroundResource(R.drawable.bg_fab);
        fab.setElevation(dp(8));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(56), dp(56));
        flp.gravity = android.view.Gravity.END;
        flp.topMargin = dp(12);
        fab.setLayoutParams(flp);
        fab.setOnClickListener(v -> showHookDialog(-1));
        root.addView(fab);

        setContentView(root);
        loadEntry();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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

    private void showHookDialog(final int editIndex) {
        View view = getLayoutInflater().inflate(R.layout.dialog_hook, null);
        final Spinner modeSpin = view.findViewById(R.id.dh_mode);
        final EditText etClass = view.findViewById(R.id.dh_class);
        final EditText etMethod = view.findViewById(R.id.dh_method);
        final EditText etParams = view.findViewById(R.id.dh_params);
        final EditText etValue = view.findViewById(R.id.dh_value);
        final EditText etField = view.findViewById(R.id.dh_field);
        final TextView fieldLabel = view.findViewById(R.id.dh_field_label);

        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, MODE_LABELS);
        modeSpin.setAdapter(sa);

        if (editIndex >= 0) {
            UserHookStore.HookEntry h = entry.hooks.get(editIndex);
            int mi = 0;
            if (UserHookStore.MODE_PARAMS.equals(h.mode)) mi = 1;
            else if (UserHookStore.MODE_INTERCEPT.equals(h.mode)) mi = 2;
            else if (UserHookStore.MODE_STATIC.equals(h.mode)) mi = 3;
            modeSpin.setSelection(mi);
            etClass.setText(h.className);
            etMethod.setText(h.methodName);
            etParams.setText(h.paramTypes);
            etValue.setText(h.value);
            etField.setText(h.fieldName);
        }

        updateFieldVisibility(modeSpin, etField, fieldLabel);
        modeSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                updateFieldVisibility(modeSpin, etField, fieldLabel);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? "编辑 Hook" : "添加 Hook")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    UserHookStore.HookEntry h = new UserHookStore.HookEntry();
                    int mi = modeSpin.getSelectedItemPosition();
                    h.mode = mi == 1 ? UserHookStore.MODE_PARAMS
                            : mi == 2 ? UserHookStore.MODE_INTERCEPT
                            : mi == 3 ? UserHookStore.MODE_STATIC
                            : UserHookStore.MODE_RETURN;
                    h.className = TextUtils.isEmpty(etClass.getText()) ? "" : etClass.getText().toString().trim();
                    h.methodName = TextUtils.isEmpty(etMethod.getText()) ? "" : etMethod.getText().toString().trim();
                    h.paramTypes = TextUtils.isEmpty(etParams.getText()) ? "" : etParams.getText().toString().trim();
                    h.value = TextUtils.isEmpty(etValue.getText()) ? "" : etValue.getText().toString().trim();
                    h.fieldName = TextUtils.isEmpty(etField.getText()) ? "" : etField.getText().toString().trim();

                    if (h.className.isEmpty()) {
                        Toast.makeText(this, "类名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (h.methodName.isEmpty() && !UserHookStore.MODE_STATIC.equals(h.mode)) {
                        Toast.makeText(this, "方法名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (UserHookStore.MODE_STATIC.equals(h.mode) && h.fieldName.isEmpty()) {
                        Toast.makeText(this, "静态变量模式需要字段名", Toast.LENGTH_SHORT).show();
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

    private void updateFieldVisibility(Spinner modeSpin, EditText etField, TextView label) {
        boolean isStatic = modeSpin.getSelectedItemPosition() == 3;
        etField.setVisibility(isStatic ? View.VISIBLE : View.GONE);
        label.setVisibility(isStatic ? View.VISIBLE : View.GONE);
    }

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
            h.mode.setText(UserHookStore.modeLabel(e.mode));
            h.cls.setText(e.className);
            StringBuilder sb = new StringBuilder();
            if (!e.methodName.isEmpty()) sb.append("方法: ").append(e.methodName);
            if (!e.paramTypes.isEmpty()) sb.append("  参数: ").append(e.paramTypes);
            if (!e.value.isEmpty()) sb.append("  值: ").append(e.value);
            if (!e.fieldName.isEmpty()) sb.append("  字段: ").append(e.fieldName);
            h.detail.setText(sb.length() == 0 ? "（点击编辑）" : sb.toString());
            h.del.setOnClickListener(v -> {
                entry.hooks.remove(pos);
                persist();
            });
            h.itemView.setOnClickListener(v -> showHookDialog(pos));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView mode, cls, detail, del;

            VH(View v) {
                super(v);
                mode = v.findViewById(R.id.hook_mode);
                cls = v.findViewById(R.id.hook_class);
                detail = v.findViewById(R.id.hook_detail);
                del = v.findViewById(R.id.hook_delete);
            }
        }
    }
}
