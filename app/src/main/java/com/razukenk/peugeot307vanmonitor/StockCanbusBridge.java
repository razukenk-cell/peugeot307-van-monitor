package com.razukenk.peugeot307vanmonitor;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v0.10: stock DebugActivity automation is deliberately disabled.
 *
 * v0.9 could click an arbitrary ImageView and continuously re-launch the
 * stock CANBUS DebugActivity. Keeping this class as a hard safety stub makes
 * stale call sites harmless during upgrades/testing.
 */
final class StockCanbusBridge {
    static final String STOCK_PACKAGE = "com.cartech.service.canbus";
    static final String DEBUG_ACTIVITY = "com.cartech.service.canbus.activity.DebugActivity";

    static boolean launch(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("bridge_enabled", false)
                .putBoolean("bridge_pending", false)
                .putString("bridge_status", "v0.10 SAFE: запуск штатного DebugActivity заблокирован")
                .putString("bridge_capture_status", "v0.10 SAFE: автоклик отсутствует")
                .apply();
        return false;
    }

    static void disable(Context context) {
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("bridge_enabled", false)
                .putBoolean("bridge_pending", false)
                .putString("bridge_status", "v0.10 SAFE: AUTO bridge выключен")
                .putString("bridge_capture_status", "v0.10 SAFE: автоклик удалён")
                .apply();
    }

    private StockCanbusBridge() {}
}
