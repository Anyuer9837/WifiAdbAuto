package com.example.wifiadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

public class BootReceiver extends BroadcastReceiver {

    private static final int PORT = 5555;
    private static final long DELAY_MS = 8000;   // 等网络起来
    private static final long RETRY_MS = 10000;  // 首次没起来再试一次

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (!RootShell.enableAdbTcp(PORT)) {
                handler.postDelayed(() -> RootShell.enableAdbTcp(PORT), RETRY_MS);
            }
            // 顺带把发现网页起起来，方便局域网找到手机
            WebService.start(context, PORT);
        }, DELAY_MS);
    }
}
