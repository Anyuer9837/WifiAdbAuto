package com.example.wifiadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP 服务：局域网内电脑访问 http://手机IP:9837 就能拿到设备 IP 和 adb 连接命令
 */
public class SimpleHttpServer implements Runnable {

    private static final String TAG = "SimpleHttpServer";

    private final int port;
    private final int adbPort;
    private final Context context;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private volatile boolean running;

    public SimpleHttpServer(int port, int adbPort, Context context) {
        this.port = port;
        this.adbPort = adbPort;
        this.context = context.getApplicationContext();
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        new Thread(this, "wifiadb-http").start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            String requestLine = reader.readLine();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // 读完请求头
            }

            String path = "/";
            if (requestLine != null && !requestLine.isEmpty()) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) path = parts[1];
            }

            String remoteIp = socket.getInetAddress() == null
                    ? null : socket.getInetAddress().getHostAddress();
            respond(socket, path, remoteIp);

        } catch (Exception e) {
            Log.w(TAG, "handle request failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void respond(Socket socket, String rawPath, String remoteIp) throws IOException {
        String path = rawPath;
        String query = null;
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            path = rawPath.substring(0, q);
            query = rawPath.substring(q + 1);
        }

        String ip = IpUtils.getLanIpAddress();
        if (ip == null) ip = "0.0.0.0";
        String connect = "adb connect " + ip + ":" + adbPort;

        byte[] body;
        String contentType;

        if (path.startsWith("/api/set")) {
            contentType = "application/json; charset=utf-8";
            body = applySettings(query, remoteIp).getBytes(StandardCharsets.UTF_8);
        } else if (path.startsWith("/ip")) {
            contentType = "text/plain; charset=utf-8";
            body = connect.getBytes(StandardCharsets.UTF_8);
        } else if (path.startsWith("/json")) {
            contentType = "application/json; charset=utf-8";
            body = ("{\"ip\":\"" + ip + "\",\"adbPort\":" + adbPort
                    + ",\"connect\":\"" + connect + "\"}").getBytes(StandardCharsets.UTF_8);
        } else {
            contentType = "text/html; charset=utf-8";
            body = buildHtml(ip, connect).getBytes(StandardCharsets.UTF_8);
        }

        OutputStream os = socket.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        os.write(body);
        os.flush();
    }

    /**
     * 网页改设置：与手机 App 上的两个开关等价
     * /api/set?adb=1&web=1
     */
    private String applySettings(String query, String remoteIp) {
        if (!isPrivateIp(remoteIp)) {
            Log.w(TAG, "拒绝非局域网的设置请求: " + remoteIp);
            return "{\"ok\":false,\"msg\":\"仅允许局域网访问\"}";
        }

        boolean wantAdb = queryParam(query, "adb", true);
        boolean wantWeb = queryParam(query, "web", true);

        boolean applied = wantAdb ? RootShell.enableAdbTcp(adbPort) : RootShell.disableAdbTcp(adbPort);
        boolean adbOn = RootShell.isPortListening(adbPort);
        boolean webOn = WebService.running;

        if (wantWeb && !webOn) {
            WebService.start(context, adbPort);
        } else if (!wantWeb && webOn) {
            // 先把响应写回去，再停服务，否则浏览器收不到结果
            new Handler(Looper.getMainLooper()).postDelayed(() -> WebService.stop(context), 500);
        }

        return "{\"ok\":true,\"adb\":" + adbOn
                + ",\"web\":" + WebService.running
                + ",\"applied\":" + applied + "}";
    }

    private static boolean queryParam(String query, String key, boolean def) {
        if (query == null) return def;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && key.equals(kv.substring(0, i))) {
                return !"0".equals(kv.substring(i + 1).trim());
            }
        }
        return def;
    }

    /** 只允许局域网（10.x / 192.168.x / 172.16-31.x / 本机）改设置 */
    private static boolean isPrivateIp(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if ("127.0.0.1".equals(ip) || "::1".equals(ip)) return true;
        if (ip.startsWith("172.")) {
            String[] p = ip.split("\\.");
            if (p.length == 4) {
                try {
                    int second = Integer.parseInt(p[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private String buildHtml(String ip, String connect) {
        boolean adbOn = RootShell.isPortListening(adbPort);
        String url = "http://" + ip + ":" + port;

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{{APP_NAME}}</title>
                <style>
                body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Microsoft YaHei",sans-serif;background:#f5f6f8;color:#1f2328;margin:0;padding:32px}
                .card{max-width:560px;margin:0 auto;background:#fff;border-radius:12px;padding:24px;box-shadow:0 2px 12px rgba(0,0,0,.08)}
                h1{font-size:18px;margin:0 0 4px}
                .sub{color:#656d76;font-size:13px;margin-bottom:20px}
                .label{color:#656d76;font-size:12px;margin-top:16px}
                .ip{font-size:34px;font-weight:600;letter-spacing:.5px;margin:4px 0}
                code{display:block;background:#0d1117;color:#e6edf3;padding:12px 14px;border-radius:8px;font-size:15px;margin-top:8px;overflow-x:auto}
                button{background:#1f6feb;color:#fff;border:0;border-radius:8px;padding:10px 16px;font-size:14px;cursor:pointer}
                button:active{opacity:.85}
                .row{display:flex;gap:8px;align-items:center;margin-top:12px}
                .status{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;margin-left:8px}
                .on{background:#dafbe1;color:#116329}
                .off{background:#ffebe9;color:#82071e}
                .cmds{margin-top:8px}
                .cmd{border-top:1px solid #f0f1f3;padding:12px 0}
                .cmd-t{font-size:13px;color:#1f2328}
                .cmd .row{margin-top:6px}
                .cmd code{flex:1;margin-top:0;font-size:13px;padding:8px 10px;white-space:nowrap;overflow-x:auto}
                .set{border:1px solid #e5e7eb;border-radius:10px;margin-top:8px;padding:4px 14px}
                .set-row{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 0}
                .set-row + .set-row{border-top:1px solid #f0f1f3}
                .set-t{font-size:14px}
                .set-d{font-size:12px;color:#656d76;margin-top:2px}
                .switch{position:relative;display:inline-block;width:46px;height:26px;flex:0 0 auto}
                .switch input{opacity:0;width:0;height:0}
                .switch span{position:absolute;inset:0;background:#d0d7de;border-radius:999px;transition:.2s}
                .switch span:before{content:"";position:absolute;width:20px;height:20px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.2s}
                .switch input:checked + span{background:#1f6feb}
                .switch input:checked + span:before{transform:translateX(20px)}
                .msg{font-size:13px;color:#1a7f37}
                .tip{color:#656d76;font-size:12px;line-height:1.7;margin-top:20px;border-top:1px solid #eee;padding-top:12px}
                </style>
                </head>
                <body>
                <div class="card">
                  <h1>{{APP_NAME}}</h1>
                  <div class="sub">手机 IP：{{URL}}</div>

                  <div class="label">设备 IP</div>
                  <div class="ip">{{IP}}<span class="status {{STATUS_CLASS}}">{{STATUS_TEXT}}</span></div>

                  <div class="label">电脑执行</div>
                  <code id="cmd">{{CONNECT}}</code>
                  <div class="row">
                    <button id="btnCopy" onclick="copyCmd()">复制命令</button>
                    <button onclick="location.reload()">刷新</button>
                  </div>

                  <div class="label" style="margin-top:24px">常用命令（点右侧复制）</div>
                  <div class="cmds">{{CMDS}}</div>

                  <div class="label" style="margin-top:24px">设置（拨动即生效，与手机 App 上的开关等价）<span id="msg" class="msg" style="margin-left:8px"></span></div>
                  <div class="set">
                    <div class="set-row">
                      <div>
                        <div class="set-t">ADB 无线调试</div>
                        <div class="set-d">端口 {{ADBPORT}}，关闭后只能用 USB 调试</div>
                      </div>
                      <label class="switch"><input type="checkbox" id="swAdb" onchange="onSw('adb',this)" {{ADB_CHECKED}}><span></span></label>
                    </div>
                    <div class="set-row">
                      <div>
                        <div class="set-t">网页发现服务</div>
                        <div class="set-d">就是本页（端口 {{WEBPORT}}）。关闭后本页打不开，需到手机 App 里重开</div>
                      </div>
                      <label class="switch"><input type="checkbox" id="swWeb" onchange="onSw('web',this)" checked><span></span></label>
                    </div>
                  </div>

                  <div class="tip">
                    1. 电脑与手机需在同一个 Wi-Fi 下<br>
                    2. 改「ADB 无线调试」会重启 adbd，电脑上已连的设备会掉线，需重新 connect
                  </div>
                </div>
                <script>
                function doCopy(t,btn){
                  if(navigator.clipboard){navigator.clipboard.writeText(t);}
                  else{var i=document.createElement('textarea');i.value=t;document.body.appendChild(i);i.select();document.execCommand('copy');i.remove();}
                  if(btn){var o=btn.innerText;btn.innerText='已复制';setTimeout(function(){btn.innerText=o;},1200);}
                }
                function copyCmd(){doCopy(document.getElementById('cmd').innerText,document.getElementById('btnCopy'));}
                function cp(btn){doCopy(btn.parentNode.querySelector('code').innerText,btn);}
                function applyNow(){
                  var adb=document.getElementById('swAdb').checked?1:0;
                  var web=document.getElementById('swWeb').checked?1:0;
                  var msg=document.getElementById('msg');
                  msg.innerText='应用中…';
                  fetch('/api/set?adb='+adb+'&web='+web+'&t='+Date.now())
                    .then(function(r){return r.json();})
                    .then(function(j){
                      if(web===1){location.reload();}
                      else{msg.innerText='网页服务已关闭，本页不再可用';document.querySelector('.card').style.opacity=.5;}
                    })
                    .catch(function(){
                      msg.innerText=(web===1)?'请求失败，请刷新重试':'网页服务已关闭，本页不再可用';
                    });
                }
                function onSw(key,el){
                  if(key==='web' && !el.checked
                     && !confirm('关闭后本页将无法访问，只能到手机 App 上重新开启。确定关闭网页发现服务吗？')){
                    el.checked=true;return;
                  }
                  applyNow();
                }
                </script>
                </body>
                </html>
                """
                .replace("{{IP}}", ip)
                .replace("{{URL}}", url)
                .replace("{{CONNECT}}", connect)
                .replace("{{STATUS_CLASS}}", adbOn ? "on" : "off")
                .replace("{{STATUS_TEXT}}", adbOn ? "ADB 监听中" : "ADB 未开启")
                .replace("{{CMDS}}", renderCommands(ip))
                .replace("{{ADBPORT}}", String.valueOf(adbPort))
                .replace("{{WEBPORT}}", String.valueOf(port))
                .replace("{{ADB_CHECKED}}", adbOn ? "checked" : "")
                .replace("{{APP_NAME}}", appName());
    }

    /** 网页名字跟手机上的 App 名字保持一致（取 Manifest 的 android:label / app_name） */
    private String appName() {
        try {
            CharSequence label = context.getPackageManager()
                    .getApplicationLabel(context.getApplicationInfo());
            if (label != null && label.length() > 0) return label.toString();
        } catch (Exception e) {
            Log.w(TAG, "读取 App 名称失败", e);
        }
        return "WifiADB Auto";
    }

    /**
     * 常用 adb 命令列表
     */
    private String renderCommands(String ip) {
        String target = ip + ":" + adbPort;
        String[][] items = {
                {"连接这台设备", "adb connect " + target},
                {"断开这台设备", "adb disconnect " + target},
                {"断开全部无线连接", "adb disconnect"},
                {"查看已连接设备", "adb devices -l"}
        };

        StringBuilder sb = new StringBuilder();
        for (String[] item : items) {
            sb.append("<div class=\"cmd\"><div class=\"cmd-t\">")
                    .append(item[0])
                    .append("</div><div class=\"row\"><code>")
                    .append(item[1])
                    .append("</code><button onclick=\"cp(this)\">复制</button></div></div>");
        }
        return sb.toString();
    }
}
