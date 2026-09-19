package com.example.wifiadb;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class RootShell {

    /**
     * 执行 root 命令（不关心输出）
     */
    public static boolean execAsRoot(String[] commands) {
        Process su = null;
        DataOutputStream os = null;
        try {
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());

            for (String cmd : commands) {
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();

            int result = su.waitFor();
            return result == 0;

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (su != null) su.destroy();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 执行 root 命令并返回标准输出
     */
    public static List<String> execAsRootWithOutput(String[] commands) {
        List<String> lines = new ArrayList<>();
        Process su = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        try {
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());
            reader = new BufferedReader(new InputStreamReader(su.getInputStream()));

            for (String cmd : commands) {
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            su.waitFor();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) reader.close();
                if (os != null) os.close();
                if (su != null) su.destroy();
            } catch (IOException ignored) {}
        }
        return lines;
    }

    /**
     * 判断某端口是否处于 LISTEN 状态（读 /proc/net/tcp、/proc/net/tcp6）
     */
    public static boolean isPortListening(int port) {
        String hexPort = String.format("%04X", port);
        List<String> lines = execAsRootWithOutput(new String[]{
                "cat /proc/net/tcp",
                "cat /proc/net/tcp6"
        });

        for (String line : lines) {
            String[] fields = line.trim().split("\\s+");
            // sl local_address rem_address st ...
            if (fields.length < 4) continue;
            String localAddress = fields[1].toUpperCase();
            String state = fields[3].toUpperCase();
            // 0A = TCP_LISTEN
            if ("0A".equals(state) && localAddress.endsWith(":" + hexPort)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在超时时间内轮询端口是否已经在监听
     */
    private static boolean waitListening(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (isPortListening(port)) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
    }

    /**
     * 开启 ADB TCP 监听：
     * 1. 写 persist 属性（重启后依然生效）
     * 2. 依次尝试多种拉起 adbd 的方式，让本次也能立即生效
     */
    public static boolean enableAdbTcp(int port) {
        writeProps(port, true);

        String[][] attempts = {
                {"setprop service.adb.tcp.port " + port, "stop adbd", "start adbd"},
                {"setprop service.adb.tcp.port " + port, "setprop ctl.restart adbd"},
                {"setprop service.adb.tcp.port " + port, "pkill -f adbd", "sleep 1", "start adbd"},
                {"setprop service.adb.tcp.port " + port, "killall adbd", "sleep 1", "start adbd"}
        };

        for (String[] cmds : attempts) {
            execAsRoot(cmds);
            if (waitListening(port, 3000)) return true;
        }
        return false;
    }

    /**
     * 关闭 ADB TCP（恢复 USB-only），同样尽量让本次立即生效
     */
    public static boolean disableAdbTcp(int port) {
        writeProps(port, false);

        String[][] attempts = {
                {"stop adbd", "start adbd"},
                {"setprop ctl.restart adbd"},
                {"pkill -f adbd", "sleep 1", "start adbd"},
                {"killall adbd", "sleep 1", "start adbd"}
        };

        for (String[] cmds : attempts) {
            execAsRoot(cmds);
            if (!waitListening(port, 3000)) return true;
        }
        return false;
    }

    /**
     * 写属性：service.* 管本次运行时，persist.* 管重启后
     */
    private static void writeProps(int port, boolean enable) {
        execAsRoot(new String[]{
                "setprop service.adb.tcp.port " + (enable ? port : 0),
                "setprop persist.adb.tcp.port " + (enable ? String.valueOf(port) : "\"\"")
        });
    }
}
