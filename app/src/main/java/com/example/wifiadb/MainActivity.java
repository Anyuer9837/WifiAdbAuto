package com.example.wifiadb;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 5555;
    private static final int REQ_NOTIFY = 1;

    private Switch swAdb;
    private Switch swWeb;
    private TextView tvUrl;
    private TextView tvCmd;
    private View rowUrl;
    private View rowCmd;
    private Button btnOpen;
    private Button btnCopy;
    private View viewBlocking;

    /** 代码里回写开关状态时，避免触发自动应用 */
    private boolean syncingSwitches;

    /** 正在应用中的标志：防止快速连点并发跑 su / 反复重启 adbd */
    private volatile boolean applying;

    /** 刚打开 App 时自动拉起网页服务，此时服务可能还没置上 running 标记 */
    private boolean webAutoStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swAdb = findViewById(R.id.sw_adb);
        swWeb = findViewById(R.id.sw_web);
        tvUrl = findViewById(R.id.tv_url);
        tvCmd = findViewById(R.id.tv_cmd);
        rowUrl = findViewById(R.id.row_url);
        rowCmd = findViewById(R.id.row_cmd);
        btnOpen = findViewById(R.id.btn_open);
        btnCopy = findViewById(R.id.btn_copy);
        viewBlocking = findViewById(R.id.view_blocking);

        btnCopy.setOnClickListener(v -> copyToClipboard(tvCmd.getText().toString()));
        btnOpen.setOnClickListener(v -> openInBrowser(tvUrl.getText().toString()));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }

        // 拨开关即应用，不需要额外的「应用」按钮
        swAdb.setOnCheckedChangeListener((v, checked) -> {
            if (syncingSwitches) return;
            if (applying) {
                revertSwitch(v, checked);
                return;
            }
            applyAsync(checked, swWeb.isChecked());
        });
        swWeb.setOnCheckedChangeListener((v, checked) -> {
            if (syncingSwitches) return;
            if (applying) {
                revertSwitch(v, checked);
                return;
            }
            applyAsync(swAdb.isChecked(), checked);
        });

        // 每次打开 App 自动开启网页发现服务（服务没开时才真正拉起，重复调用无副作用）
        if (!WebService.running) {
            WebService.start(this, PORT);
            webAutoStarted = true;
        }

        refreshStatus();
    }

    /**
     * 读取当前真实状态
     */
    private void refreshStatus() {
        new Thread(() -> {
            boolean root = RootShell.execAsRoot(new String[]{"echo test"});
            boolean on = root && RootShell.isPortListening(PORT);
            boolean web = WebService.running || webAutoStarted;
            runOnUiThread(() -> {
                syncingSwitches = true;
                swAdb.setChecked(on);
                swWeb.setChecked(web);
                syncingSwitches = false;
                if (webAutoStarted) {
                    // 服务刚拉起，稍后再回读一次真实状态，端口占用等失败会在这里暴露
                    webAutoStarted = false;
                    swWeb.postDelayed(this::refreshStatus, 800);
                }
                if (!root) Toast.makeText(this, "⚠️ 未获取 Root 权限", Toast.LENGTH_LONG).show();
                updateInfo(web);
            });
        }).start();
    }

    /**
     * 应用期间点了开关：把开关弹回原状态，避免和正在跑的任务打架
     */
    private void revertSwitch(CompoundButton view, boolean checked) {
        syncingSwitches = true;
        view.setChecked(!checked);
        syncingSwitches = false;
        Toast.makeText(this, "正在应用，请稍候", Toast.LENGTH_SHORT).show();
    }

    private void showBlocking(boolean show) {
        if (viewBlocking == null) return;
        viewBlocking.setVisibility(show ? View.VISIBLE : View.GONE);
        swAdb.setEnabled(!show);
        swWeb.setEnabled(!show);
    }

    /**
     * 开关：ADB 本次立即生效 + 开机保持；网页发现服务随开关启停
     */
    private void applyAsync(boolean wantAdb, boolean wantWeb) {
        if (applying) return;
        applying = true;
        showBlocking(true);

        new Thread(() -> {
            boolean adbOk = wantAdb
                    ? RootShell.enableAdbTcp(PORT)
                    : RootShell.disableAdbTcp(PORT);

            runOnUiThread(() -> {
                if (wantWeb) WebService.start(this, PORT);
                else WebService.stop(this);

                Toast.makeText(this, adbOk ? "已生效" : "本次未生效，已写入开机配置，重启手机后生效",
                        Toast.LENGTH_SHORT).show();
                showBlocking(false);
                applying = false;

                // 稍等一下再回读真实状态，让 WebService 有时间更新 running
                swAdb.postDelayed(this::refreshStatus, 500);
            });
        }).start();
    }

    /**
     * 两行信息各自独立：
     * - 网页链接只在网页发现服务开着时显示
     * - adb 连接命令跟网页服务无关，只要拿到 IP 就一直显示
     */
    private void updateInfo(boolean webOn) {
        String ip = IpUtils.getLanIpAddress();

        if (webOn && ip != null) {
            tvUrl.setText("http://" + ip + ":" + WebService.WEB_PORT);
            rowUrl.setVisibility(View.VISIBLE);
        } else {
            rowUrl.setVisibility(View.GONE);
        }

        if (ip != null) {
            tvCmd.setText("adb connect " + ip + ":" + PORT);
            rowCmd.setVisibility(View.VISIBLE);
        } else {
            rowCmd.setVisibility(View.GONE);
        }
    }

    /**
     * 复制 adb 连接命令
     */
    private void copyToClipboard(String text) {
        if (text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("adb command", text));
        Toast.makeText(this, "已复制：" + text, Toast.LENGTH_SHORT).show();
    }

    /**
     * 用系统浏览器打开网页
     */
    private void openInBrowser(String url) {
        if (url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }
}
