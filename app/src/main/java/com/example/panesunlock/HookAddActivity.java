package com.example.panesunlock;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.List;

/**
 * 「新增配置 / 编辑配置」页（图 5）：模式、时机、类名、方法、参数、返回值、返回类型、备注。
 */
public class HookAddActivity extends AppCompatActivity {

    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_INDEX = "index";

    private static final String[] PARAM_PRESETS = {
            "", "int", "boolean", "long", "java.lang.String",
            "int,boolean,java.lang.String", "java.lang.String,java.lang.String",
            "android.content.Context", "int,int", "byte[]"
    };
    private static final String[] RETURN_TYPES = {
            "", "boolean", "int", "long", "float", "double", "short", "byte", "char",
            "void", "java.lang.String", "java.lang.Object"
    };

    private String pkg;
    private int editIndex = -1;
    private UserHookStore.AppEntry entry;
    private List<UserHookStore.AppEntry> apps;

    private String mode = UserHookStore.MODE_RETURN;
    private boolean after = false;
    private SwitchCompat attach;
    private TextView modeView, pointView, paramsView, returnTypeView;
    private EditText etClass, etMethod, etValue, etMemo, etField;

    public static Intent intent(Context ctx, String pkg) {
        return intent(ctx, pkg, -1);
    }

    public static Intent intent(Context ctx, String pkg, int index) {
        Intent i = new Intent(ctx, HookAddActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        i.putExtra(EXTRA_INDEX, index);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hook_add);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        editIndex = getIntent().getIntExtra(EXTRA_INDEX, -1);
        if (pkg == null || pkg.isEmpty()) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.ha_title)).setText(editIndex >= 0 ? "编辑配置" : "新增配置");
        findViewById(R.id.ha_back).setOnClickListener(v -> finish());
        findViewById(R.id.ha_save).setOnClickListener(v -> save());

        attach = findViewById(R.id.ha_attach);
        modeView = findViewById(R.id.ha_mode);
        pointView = findViewById(R.id.ha_point);
        paramsView = findViewById(R.id.ha_params);
        returnTypeView = findViewById(R.id.ha_return_type);
        etClass = findViewById(R.id.ha_class);
        etMethod = findViewById(R.id.ha_method);
        etValue = findViewById(R.id.ha_value);
        etMemo = findViewById(R.id.ha_memo);
        etField = findViewById(R.id.ha_field);
        updateFieldVisibility();

        modeView.setOnClickListener(v -> chooseMode());
        pointView.setOnClickListener(v -> choosePoint());
        paramsView.setOnClickListener(v -> chooseParams());
        returnTypeView.setOnClickListener(v -> chooseReturnType());
        findViewById(R.id.ha_smali).setOnClickListener(v -> fillFromSmali());

        apps = UserHookStore.load(this);
        entry = null;
        for (UserHookStore.AppEntry a : apps) {
            if (a.pkg.equals(pkg)) {
                entry = a;
                break;
            }
        }
        if (entry == null) {
            Toast.makeText(this, "应用配置不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (editIndex >= 0 && editIndex < entry.hooks.size()) {
            fillFrom(entry.hooks.get(editIndex));
        } else {
            // 从「Hook 配置 → 导入」传来的 smali 自动预填
            String smali = HookEditActivity.ClipboardHolder.smali;
            if (smali != null && !smali.isEmpty()) {
                HookEditActivity.ClipboardHolder.smali = null;
                SmaliParser.Result r = SmaliParser.parse(smali);
                if (r.ok) {
                    if (!r.className.isEmpty()) etClass.setText(r.className);
                    etMethod.setText(r.methodName);
                    paramsView.setText(r.paramTypes);
                    if (!r.guessedValue.isEmpty()) etValue.setText(r.guessedValue);
                    if (!r.returnType.isEmpty()) returnTypeView.setText(r.returnType);
                }
            }
        }
    }

    private void updateFieldVisibility() {
        boolean show = UserHookStore.MODE_STATIC.equals(mode)
                || UserHookStore.MODE_INSTANCE.equals(mode)
                || UserHookStore.MODE_LOG_STATIC.equals(mode)
                || UserHookStore.MODE_LOG_INSTANCE.equals(mode);
        etField.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void fillFrom(UserHookStore.HookEntry h) {
        mode = h.mode;
        after = UserHookStore.MODE_RETURN_AFTER.equals(h.mode);
        if (after) mode = UserHookStore.MODE_RETURN;
        modeView.setText(UserHookStore.modeLabel(mode));
        pointView.setText(after ? "方法执行后" : "方法执行前");
        updateFieldVisibility();
        attach.setChecked(h.useAttach);
        etField.setText(h.fieldName);
        etClass.setText(h.className);
        etMethod.setText(h.methodName);
        paramsView.setText(h.paramTypes);
        returnTypeView.setText(h.returnType);
        etValue.setText(h.value);
        etMemo.setText(h.memo);
    }

    private void chooseMode() {
        // 「返回值 + 时机=执行后」表达返回值+，模式列表不再重复列出
        final List<String> modes = new ArrayList<>();
        int cur = 0;
        for (String m : UserHookStore.ALL_MODES) {
            if (UserHookStore.MODE_RETURN_AFTER.equals(m)) continue;
            if (m.equals(mode)) cur = modes.size();
            modes.add(m);
        }
        String[] labels = new String[modes.size()];
        for (int i = 0; i < modes.size(); i++) {
            labels[i] = UserHookStore.modeLabel(modes.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Hook 模式")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    mode = modes.get(which);
                    modeView.setText(UserHookStore.modeLabel(mode));
                    updateFieldVisibility();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void choosePoint() {
        new AlertDialog.Builder(this)
                .setTitle("Hook 时机")
                .setSingleChoiceItems(new String[]{"方法执行前", "方法执行后"},
                        after ? 1 : 0, (d, which) -> {
                            after = which == 1;
                            pointView.setText(after ? "方法执行后" : "方法执行前");
                            d.dismiss();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseParams() {
        new AlertDialog.Builder(this)
                .setTitle("参数类型")
                .setItems(new String[]{
                        "（留空 · Hook 同名全部重载）", "int", "boolean", "long",
                        "java.lang.String", "int,boolean,java.lang.String",
                        "java.lang.String,java.lang.String", "android.content.Context",
                        "int,int", "byte[]", "手动输入…"
                }, (d, which) -> {
                    if (which == 0) {
                        paramsView.setText("");
                    } else if (which == 10) {
                        showInputDialog("参数类型", paramsView.getText().toString(), paramsView);
                    } else {
                        paramsView.setText(PARAM_PRESETS[which]);
                    }
                })
                .show();
    }

    private void chooseReturnType() {
        new AlertDialog.Builder(this)
                .setTitle("返回类型")
                .setItems(new String[]{
                        "（自动推断）", "boolean", "int", "long", "float", "double",
                        "short", "byte", "char", "void", "java.lang.String",
                        "java.lang.Object", "手动输入…"
                }, (d, which) -> {
                    if (which == 0) {
                        returnTypeView.setText("");
                    } else if (which == 12) {
                        showInputDialog("返回类型", returnTypeView.getText().toString(), returnTypeView);
                    } else {
                        returnTypeView.setText(RETURN_TYPES[which]);
                    }
                })
                .show();
    }

    private void showInputDialog(String title, String current, TextView target) {
        EditText et = new EditText(this);
        et.setText(current);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> target.setText(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
        et.setPadding(pad, pad, pad, pad);
    }

    private void fillFromSmali() {
        String text = clipboardText();
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        SmaliParser.Result r = SmaliParser.parse(text);
        if (!r.ok) {
            Toast.makeText(this, "解析失败：" + r.error, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!r.className.isEmpty()) etClass.setText(r.className);
        etMethod.setText(r.methodName);
        paramsView.setText(r.paramTypes);
        if (!r.guessedValue.isEmpty()) etValue.setText(r.guessedValue);
        if (!r.returnType.isEmpty()) returnTypeView.setText(r.returnType);
        Toast.makeText(this, "已从 smali 填充", Toast.LENGTH_SHORT).show();
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

    private void save() {
        String className = textOf(etClass);
        String methodName = textOf(etMethod);
        String params = paramsView.getText().toString().trim();
        String value = textOf(etValue);
        String returnType = returnTypeView.getText().toString().trim();
        String memo = textOf(etMemo);

        if (className.isEmpty()) {
            Toast.makeText(this, "类名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean logMode = UserHookStore.MODE_LOG_PARAMS.equals(mode)
                || UserHookStore.MODE_LOG_RETURN.equals(mode)
                || UserHookStore.MODE_LOG_BOTH.equals(mode)
                || UserHookStore.MODE_LOG_STATIC.equals(mode)
                || UserHookStore.MODE_LOG_INSTANCE.equals(mode);
        if (methodName.isEmpty() && !UserHookStore.MODE_STATIC.equals(mode) && !logMode) {
            Toast.makeText(this, "方法名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean fieldMode = UserHookStore.MODE_STATIC.equals(mode)
                || UserHookStore.MODE_INSTANCE.equals(mode)
                || UserHookStore.MODE_LOG_STATIC.equals(mode)
                || UserHookStore.MODE_LOG_INSTANCE.equals(mode);
        String fieldName = textOf(etField);
        if (fieldMode && fieldName.isEmpty()) {
            Toast.makeText(this, "该模式需要填写字段名 / 变量名", Toast.LENGTH_SHORT).show();
            return;
        }
        if (UserHookStore.MODE_PARAMS.equals(mode) && params.isEmpty()) {
            Toast.makeText(this, "参数替换模式需要填写参数类型", Toast.LENGTH_SHORT).show();
            return;
        }

        UserHookStore.HookEntry h = new UserHookStore.HookEntry();
        h.mode = (UserHookStore.MODE_RETURN.equals(mode) && after)
                ? UserHookStore.MODE_RETURN_AFTER : mode;
        h.className = className;
        h.methodName = methodName;
        h.paramTypes = params;
        h.value = value;
        h.returnType = returnType;
        h.memo = memo;
        h.fieldName = fieldName;
        h.useAttach = attach.isChecked();
        h.enabled = true;

        apps = UserHookStore.load(this);
        UserHookStore.AppEntry target = null;
        for (UserHookStore.AppEntry a : apps) {
            if (a.pkg.equals(pkg)) {
                target = a;
                break;
            }
        }
        if (target == null) {
            Toast.makeText(this, "应用配置不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editIndex >= 0 && editIndex < target.hooks.size()) {
            target.hooks.set(editIndex, h);
        } else {
            target.hooks.add(h);
        }
        UserHookStore.save(this, apps);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private static String textOf(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
