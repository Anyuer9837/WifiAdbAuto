# WifiAdbAuto

一个 Android 小工具（需 root）：让手机开机后自动开启 ADB 无线调试（TCP 5555），电脑在同一 Wi-Fi 下直接 `adb connect 手机IP:5555`，不用插线。

功能只有三块：

- **ADB 无线调试开关**：写入 `persist.adb.tcp.port` 并重启 adbd，立即生效且重启手机后保持；开机广播（`BootReceiver`）再兜一次底
- **网页发现服务开关**：手机上跑一个 9837 端口的前台服务，电脑浏览器打开 `http://手机IP:9837` 就能看到手机 IP、`adb connect` 命令，以及和 App 里一样的两
个开关（网页上改完立即生效），省得在电脑上用的时候还切回手机
- **App 首页**：两个开关各自一张卡片，卡片内直接给出 `adb connect IP:5555`（一键复制）和 `http://IP:9837`（一键用浏览器打开）；打开 App 时网页服务会自动开启

## 编译

环境要求：

| 项 | 版本 |
| --- | --- |
| JDK | **17**（AGP 8.1 强制要求，JDK 21 会导致 `androidJdkImage` 失败） |
| Gradle | 8.11.1（用仓库自带的 wrapper，无需单独装） |
| Android Gradle Plugin | 8.1.0 |
| compileSdk / targetSdk | 34 |
| minSdk | 26（Android 8.0） |
| Android Studio | Iguana 及以上（或直接用命令行） |

命令行：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次编译会自动下载 Gradle 和依赖，时间较长属正常。

> 项目里的 `org.gradle.java.home` 写的是本机 JDK 17 路径（AGP 8.1 只认 17，JDK 21 会挂）。换机器编译改成自己的路径即可，Android Studio 用户也可以直接在 Settings → Gradle → Gradle JDK 里选 17。
