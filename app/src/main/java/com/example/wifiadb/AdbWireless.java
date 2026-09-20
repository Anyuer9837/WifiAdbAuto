package com.example.wifiadb;

import android.os.Build;

/**
 * 判断系统有没有「无线调试」这一项，用来决定引导走哪条路。
 *
 * 本 App 不做任何写入也不读系统状态：无线调试的开关一律让用户去系统设置里操作，
 * 这里只负责告诉用户「你的系统版本有没有这个功能」。
 */
public class AdbWireless {

    private AdbWireless() {}

    /**
     * 无线调试是 Android 11（API 30）才加入的系统功能，10 及以下根本没这一项，
     * 引导给的是完全不同的另一条路。
     */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

}
