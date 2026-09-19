# WifiADB Auto

开机自动开启 ADB TCP 监听（端口 5555），局域网内电脑可直接 `adb connect 手机IP:5555`。

## 编译

用 Android Studio 打开本项目根目录，Build → Make Project，或直接：

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用步骤

1. 安装 APK
2. 打开 APP → 直接拨两个开关（拨动即应用，没有「应用」按钮）
3. 首次会弹 root 授权窗口 → 点允许（建议勾选「永久允许」）
4. 拨完即生效，无需重启；`persist.adb.tcp.port` 会保留，重启后依然自动开启
5. 每次打开 App 会自动开启「网页发现服务」（不用手动拨，想关随时拨回去）
6. 卡片内直接给出结果：ADB 卡片显示 `adb connect 手机IP:5555`（右侧「复制」），网页卡片显示 `http://手机IP:9837`（右侧「打开」直接拉起浏览器）；电脑浏览器打开网页即可看到 IP、连接命令，以及和 App 里一样的开关

## 局域网发现网页（:9837）

手机会跑一个极简 HTTP 服务（前台服务，端口 9837），解决「局域网里扫不到 adbd 设备、不知道手机 IP」的问题。

| 地址 | 返回 |
| --- | --- |
| `http://手机IP:9837/` | 网页：IP、ADB 状态，常用 adb 命令（connect / disconnect 本机 / disconnect 全部 / devices -l）一键复制，以及**和 App 相同的两个开关**（ADB 无线调试 / 网页发现服务），网页上改完立即生效 |
| `http://手机IP:9837/ip` | 纯文本：`adb connect 192.168.1.5:5555` |
| `http://手机IP:9837/json` | JSON：`{"ip":"...","adbPort":5555,"connect":"..."}` |
| `http://手机IP:9837/api/set?adb=1&web=1` | 改设置（JSON 返回）。`adb`/`web` 传 `1`/`0`，省略视为 `1`；只允许局域网来源 IP 调用 |

网页上的开关和 App 里完全等价：改「ADB 无线调试」会重启 adbd（电脑上已连的设备会掉线，需重新 connect）；
把「网页发现服务」关掉后本页立刻打不开，只能回手机 App 重开（网页会先弹确认）。这样可以全程只在电脑上操作，不用再切到手机。

脚本里可以直接用：

```bash
IP=192.168.1.5
adb connect $(curl -s http://$IP:9837/ip | awk '{print $3}')
```

连 IP 都懒得找的话，扫 9837 比扫 5555 可靠得多（我们的服务一定会应答）：

```bash
nmap -p 9837 --open 192.168.1.0/24
# 或
for i in $(seq 1 254); do echo -n "192.168.1.$i "; curl -s -m 1 http://192.168.1.$i:9837/ip; echo; done | grep 5555
```

## 电脑连接

```bash
adb connect 手机IP:5555
adb devices
```

## 注意事项

- Android 8+ 需要首次手动打开一次 APP，才能收到开机广播
- 端口默认为 5555，想改的话修改 `BootReceiver.java` 和 `MainActivity.java` 中的 `PORT` 常量
- 公共 Wi-Fi 下建议改高位端口，避免被同网段扫描
- 拨动开关时会依次尝试 `stop/start adbd` → `setprop ctl.restart adbd` → `killall adbd`，并用 `/proc/net/tcp` 校验端口是否真在 LISTEN；全部失败时状态栏会提示「本次未生效，重启后生效」（此时 `persist.adb.tcp.port` 已写入，重启仍会开启）
- 关闭时会同时清除 `persist.adb.tcp.port`，不会重启后又自动打开
- 发现网页跑在前台服务里（通知栏常驻一条「ADB 发现页已开启」），Android 13+ 需要允许通知权限，否则看不到常驻通知但服务仍在
- 发现网页端口 9837 定义在 `WebService.WEB_PORT`；除查询外，还提供 `/api/set` 改设置，但**仅限局域网来源 IP**（10.x / 192.168.x / 172.16-31.x），公网来源会被拒绝
