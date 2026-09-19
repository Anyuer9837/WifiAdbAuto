package com.example.wifiadb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * 前台服务：常驻跑一个 9837 端口的网页，方便局域网电脑查到手机 IP
 */
public class WebService extends Service {

    public static final int WEB_PORT = 9837;
    public static final String EXTRA_ADB_PORT = "adb_port";

    private static final String CHANNEL_ID = "wifiadb_web";
    private static final int NOTIFICATION_ID = 9837;

    public static volatile boolean running = false;

    private SimpleHttpServer server;

    public static void start(Context context, int adbPort) {
        Intent intent = new Intent(context, WebService.class);
        intent.putExtra(EXTRA_ADB_PORT, adbPort);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, WebService.class));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int adbPort = intent != null ? intent.getIntExtra(EXTRA_ADB_PORT, 5555) : 5555;

        if (server == null || !server.isRunning()) {
            server = new SimpleHttpServer(WEB_PORT, adbPort, this);
            try {
                server.start();
                running = true;
                startForegroundCompat(buildNotification());
            } catch (Exception e) {
                e.printStackTrace();
                running = false;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        running = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private void startForegroundCompat(Notification notification) {
        // Android 14 起的类型在 Manifest 的 android:foregroundServiceType 中声明
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ADB 发现服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("局域网网页，用于查看手机 IP");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        String ip = IpUtils.getLanIpAddress();
        String url = "http://" + (ip == null ? "?" : ip) + ":" + WEB_PORT;

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_adb)
                .setContentTitle("ADB 发现页已开启")
                .setContentText(url)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(url + "\nadb connect " + ip + ":5555"))
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
