package com.example.panesunlock;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 全屏「选择应用」页（SimpleHook 式）：
 * 展示所有已安装应用（含系统应用），复选框多选 + 搜索，确定后返回结果。
 */
public class AppPickerActivity extends AppCompatActivity {

    private final List<AppInfo> all = new ArrayList<>();
    private final List<AppInfo> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private PickAdapter adapter;
    private TextView confirm;

    private static class AppInfo {
        String pkg;
        String label;
        android.graphics.drawable.Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        findViewById(R.id.pick_back).setOnClickListener(v -> finish());
        confirm = findViewById(R.id.pick_confirm);
        confirm.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                Toast.makeText(this, "请先勾选至少一个应用", Toast.LENGTH_SHORT).show();
                return;
            }
            ArrayList<String> pkgs = new ArrayList<>();
            ArrayList<String> labels = new ArrayList<>();
            for (AppInfo a : all) {
                if (selected.contains(a.pkg)) {
                    pkgs.add(a.pkg);
                    labels.add(a.label);
                }
            }
            getIntent().putExtra("pkgs", pkgs);
            getIntent().putExtra("labels", labels);
            setResult(RESULT_OK, getIntent());
            finish();
        });

        adapter = new PickAdapter();
        RecyclerView list = findViewById(R.id.pick_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        EditText search = findViewById(R.id.pick_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                refilter(s.toString());
            }
        });

        loadApps();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        try {
            List<ApplicationInfo> ais = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : ais) {
                AppInfo info = new AppInfo();
                info.pkg = ai.packageName;
                info.label = String.valueOf(pm.getApplicationLabel(ai));
                info.icon = pm.getApplicationIcon(ai);
                all.add(info);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(all, (a, b) -> a.label.compareToIgnoreCase(b.label));
        refilter("");
    }

    private void refilter(String q) {
        String t = q == null ? "" : q.trim().toLowerCase();
        shown.clear();
        for (AppInfo a : all) {
            if (t.isEmpty() || a.label.toLowerCase().contains(t) || a.pkg.toLowerCase().contains(t)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
        updateConfirm();
    }

    private void updateConfirm() {
        confirm.setText("确定（已选 " + selected.size() + "）");
    }

    private class PickAdapter extends RecyclerView.Adapter<PickAdapter.VH> {

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(AppPickerActivity.this)
                    .inflate(R.layout.item_app_pick, parent, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            final AppInfo a = shown.get(pos);
            h.icon.setImageDrawable(a.icon);
            h.name.setText(a.label);
            h.pkg.setText(a.pkg);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(selected.contains(a.pkg));
            h.itemView.setOnClickListener(v -> {
                if (selected.contains(a.pkg)) {
                    selected.remove(a.pkg);
                    h.check.setChecked(false);
                } else {
                    selected.add(a.pkg);
                    h.check.setChecked(true);
                }
                updateConfirm();
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name, pkg;
            CheckBox check;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.pick_icon);
                name = v.findViewById(R.id.pick_name);
                pkg = v.findViewById(R.id.pick_pkg);
                check = v.findViewById(R.id.pick_check);
            }
        }
    }
}
