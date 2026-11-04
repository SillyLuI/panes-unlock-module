package com.example.panesunlock;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvInfo = findViewById(R.id.tvInfo);
        TextView tvToken = findViewById(R.id.tvToken);
        Button btnCopy = findViewById(R.id.btnCopy);

        String token = TrialToken.generate();

        StringBuilder sb = new StringBuilder();
        sb.append("格窗解锁模块（LSPosed）\n\n");
        sb.append("本模块做两件事：\n");
        sb.append("① 在运行时强制格窗工作台的 LICENSE_UNLOCKED / LICENSE_TRIAL 为已解锁\n");
        sb.append("   （覆盖 App 与 SystemUI 宿主进程，客户端无功能级闸门，强制即全开）；\n");
        sb.append("② 自动生成并注入合法的“永久试用令牌”（HMAC 校验可通过，不绑机器码）。\n\n");
        sb.append("使用方法：\n");
        sb.append("1. 在 LSPosed 中启用本模块；\n");
        sb.append("2. 作用域勾选：io.github.yishisanren.panes 与 com.android.systemui；\n");
        sb.append("3. 软重启后打开格窗，设置页“解锁”卡片显示已解锁。\n\n");
        sb.append("说明：你提供的机器码仅用于付费码（ECDSA 私钥签名，无法伪造），\n");
        sb.append("本模块走的是强制 + 试用令牌路线，不需要也不依赖机器码。");
        tvInfo.setText(sb.toString());
        tvToken.setText(token);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("trial_token", token));
                Toast.makeText(this, "已复制试用令牌", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
