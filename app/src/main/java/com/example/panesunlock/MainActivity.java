package com.example.panesunlock;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.Scanner;

public class MainActivity extends Activity {

    private List<HookConf.AppEntry> apps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apps = loadApps();
        ListView list = findViewById(R.id.list);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int i) {
                return apps.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) {
                    v = LayoutInflater.from(MainActivity.this)
                            .inflate(R.layout.item_app, p, false);
                }
                HookConf.AppEntry e = apps.get(i);
                ((TextView) v.findViewById(R.id.name)).setText(e.name);
                ((TextView) v.findViewById(R.id.pkg)).setText(e.pkg);

                TextView note = v.findViewById(R.id.note);
                if (e.note != null && !e.note.isEmpty()) {
                    note.setVisibility(View.VISIBLE);
                    note.setText(e.note);
                } else {
                    note.setVisibility(View.GONE);
                }

                TextView status = v.findViewById(R.id.status);
                boolean supported = !e.rules.isEmpty()
                        || "io.github.yishisanren.panes".equals(e.pkg);
                if (supported) {
                    status.setText("已支持");
                    status.setTextColor(0xFF37D67A);
                    status.setBackgroundResource(R.drawable.bg_chip_ok);
                } else {
                    status.setText("待补 hook");
                    status.setTextColor(0xFFFFB020);
                    status.setBackgroundResource(R.drawable.bg_chip_warn);
                }
                return v;
            }
        });
    }

    private List<HookConf.AppEntry> loadApps() {
        String json = null;
        try {
            InputStream is = getAssets().open("hooks.json");
            json = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
        } catch (Throwable t) {
            // 资源不可用时回退到内置默认配置
        }
        if (json == null) json = HookConf.DEFAULT_JSON;
        return HookConf.parse(json);
    }
}
