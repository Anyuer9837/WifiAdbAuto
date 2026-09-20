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
import android.provider.Settings;
import android.text.Editable;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 5555;
    private static final int REQ_NOTIFY = 1;

    private Switch swAdb;
    private Switch swWeb;
    private TextView tvUrl;
    private TextView tvCmd;
    private TextView tvWifiGuide;
    private View rowUrl;
    private View rowCmd;
    private Button btnOpen;
    private Button btnCopy;
    private Button btnSysSettings;
    private Button btnGuide;
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

        tvWifiGuide = findViewById(R.id.tv_wifi_guide);
        btnSysSettings = findViewById(R.id.btn_sys_settings);
        btnGuide = findViewById(R.id.btn_guide);
        btnGuide.setOnClickListener(v -> showGuideDialog());
        btnSysSettings.setOnClickListener(v -> openWirelessSettings());

        tvWifiGuide.setText(buildGuideSteps());

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
     * 卡片上直接列出的精简步骤（按品牌 + 系统版本适配），完整版在对话框里
     */
    private String buildGuideSteps() {
        if (!AdbWireless.isSupported()) {
            return "① 这个系统版本没有无线调试（Android 11 才加入）\n"
                    + "② 点「查看完整步骤」看 root / 插线两种替代方案";
        }
        return "① 解锁开发者选项：" + aboutPath() + "，连点 7 次\n"
                + "② " + devOptionsPath() + " → 打开「无线调试」\n"
                + "③ 电脑先配对一次：adb pair IP:配对端口（配对码在手机无线调试页）\n"
                + "④ 再连接：adb connect IP:连接端口（端口每次开启都变，在无线调试页顶部看）\n"
                + "⑤ 电脑和手机要在同一网段，否则连不上";
    }

    /**
     * 打开系统的无线调试页；直达失败就退回开发者选项
     */
    private void openWirelessSettings() {
        try {
            startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));
            return;
        } catch (Exception ignored) {}

        try {
            Intent direct = new Intent();
            direct.setClassName("com.android.settings",
                    "com.android.settings.Settings$WirelessDebuggingSettingsActivity");
            startActivity(direct);
            return;
        } catch (Exception ignored) {}

        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 完整引导：一套普适流程 + 按品牌自动适配的入口路径。
     * 流程本身（解锁 → 开无线调试 → 配对 → 连接）在所有 Android 11+ 机型上一致，
     * 差别只在「关于手机 / 开发者选项」藏在设置里的哪一层，这里按厂商给出对应路径。
     */
    private void showGuideDialog() {
        // Android 10 及以下压根没有无线调试，引导给的是完全不同的另一条路
        if (!AdbWireless.isSupported()) {
            showLegacyGuide();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("本机 Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("） / ")
                .append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
                .append(versionNote()).append("\n");

        sb.append("\n① 解锁开发者选项")
                .append("\n").append(aboutPath())
                .append("\n连点 7 次，直到提示「您已处于开发者模式」\n");

        sb.append("\n② 打开「无线调试」")
                .append("\n").append(devOptionsPath())
                .append("\n在「调试」分组里找到「无线调试」并打开（英文 ROM 叫 Wireless debugging）")
                .append("\n点「去开发者选项」会跳到开发者选项页；无线调试是它的子页面，")
                .append("系统没给第三方 App 直达入口，需要你手动往下翻找\n");

        sb.append("\n③ 配对（同一台电脑只需做一次）")
                .append("\n无线调试 →「使用配对码配对设备」→ 记下页面上的 IP:配对端口 和 配对码")
                .append("\n电脑执行：adb pair IP:配对端口，然后输入配对码\n");

        sb.append("\n④ 连接")
                .append("\n注意：配对端口 ≠ 连接端口。连接端口在无线调试页顶部「IP 地址和端口」那一行，")
                .append("每次开启都会变，所以每次都要回来看一眼")
                .append("\n电脑执行：adb connect IP:连接端口\n");

        sb.append("\n⑤ 连不上的话先查这两点")
                .append("\n· 电脑和手机必须在同一网段（跨网段 mDNS 和连接都会失败）")
                .append("\n· 断开 Wi-Fi 或重启手机后，无线调试会被系统关掉，需重新开一次\n");

        new AlertDialog.Builder(this)
                .setTitle("无线调试完整步骤")
                .setMessage(sb.toString())
                .setNeutralButton("去开发者选项", (d, w) -> openWirelessSettings())
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * Android 10 及以下没有无线调试，给两条替代路线：有 root 走本 App 的 root 开关，
     * 没 root 就先插一次线用 adb tcpip 切到 TCP 模式。
     */
    private void showLegacyGuide() {
        StringBuilder sb = new StringBuilder();
        sb.append("本机 Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
                .append("无线调试是 Android 11 才加入的系统功能，这个版本没有，"
                        + "设置里也找不到这一项。有两条替代路线：\n");

        sb.append("\n路线 A：手机已 root（推荐，开机自动恢复）")
                .append("\n用本页上方「ADB 无线调试」卡片开（端口 5555，需 root），不需要配对")
                .append("\n电脑执行：adb connect IP:5555\n");

        sb.append("\n路线 B：手机没 root（每次重启都要重插一次线）")
                .append("\n1. 插 USB，开发者选项里打开「USB 调试」")
                .append("\n2. 电脑执行：adb tcpip 5555")
                .append("\n3. 拔线，电脑执行：adb connect IP:5555")
                .append("\n手机重启后 adbd 会回到 USB 模式，需重做第 2 步\n");

        sb.append("\n注意：路线 B 走的是明文 TCP，同一 Wi-Fi 下谁都能连上，"
                + "用完记得 adb disconnect，公共网络慎用。");

        new AlertDialog.Builder(this)
                .setTitle("Android 10 及以下的替代方案")
                .setMessage(sb.toString())
                .setNeutralButton("去开发者选项", (d, w) -> openWirelessSettings())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 无线调试在各版本上的行为差异，只挑跟当前版本相关的那几条说 */
    private String versionNote() {
        int sdk = Build.VERSION.SDK_INT;
        StringBuilder sb = new StringBuilder();
        sb.append("本版本须知：");
        if (sdk <= Build.VERSION_CODES.S_V2) {          // Android 11 / 12 / 12L
            sb.append("配对用配对码，部分 ROM 还多给一个「二维码配对」入口；");
        } else {                                         // 13+
            sb.append("配对用配对码（二维码入口有的 ROM 不给，属正常现象，以页面实际显示的为准）；");
        }
        sb.append("连接端口每次开启都随机分配；")
                .append("「无线调试」和「USB 调试」是两个独立开关，不必先开 USB 调试；")
                .append("电脑 adb 需 platform-tools 30.0.0 以上才支持 adb pair（建议用最新版）");
        return sb.toString();
    }

    /** 各 ROM 的「关于手机」藏在不同层级，按厂商给出对应路径；认不出来的走原生路径 */
    private String aboutPath() {
        switch (brand()) {
            case "xiaomi":   return "设置 → 我的设备 → 全部参数 → 连点「OS 版本」";
            case "samsung":  return "设置 → 关于手机 → 软件信息 → 连点「编译编号」";
            case "huawei":   return "设置 → 关于手机 → 连点「版本号」";
            case "oppo":     return "设置 → 关于本机 → 版本信息 → 连点「版本号」";
            case "vivo":     return "设置 → 系统管理 → 关于手机 → 连点「软件版本号」";
            default:         return "设置 → 关于手机 → 连点「版本号」（原生 / Pixel 就在这一层）";
        }
    }

    /** 开发者选项解锁后在各 ROM 里的位置 */
    private String devOptionsPath() {
        switch (brand()) {
            case "xiaomi":  return "设置 → 更多设置 → 开发者选项";
            case "samsung": return "设置 → 系统 → 开发者选项";
            case "huawei":  return "设置 → 系统和更新 → 开发人员选项";
            case "oppo":    return "设置 → 其他设置（或系统设置）→ 开发者选项";
            case "vivo":    return "设置 → 系统管理 → 开发者选项";
            default:        return "设置 → 系统 → 开发者选项";
        }
    }

    /** 把厂商归一到几家主流 ROM，认不出来的按原生 ROM 处理 */
    private String brand() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        String b = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.US);
        String s = m + " " + b;
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) return "xiaomi";
        if (s.contains("samsung")) return "samsung";
        if (s.contains("huawei") || s.contains("honor")) return "huawei";
        if (s.contains("oppo") || s.contains("oneplus") || s.contains("realme")) return "oppo";
        if (s.contains("vivo") || s.contains("iqoo")) return "vivo";
        return "aosp";
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
