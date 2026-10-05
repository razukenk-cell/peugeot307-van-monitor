package com.razukenk.peugeot307vanmonitor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

final class StockCanbusBridge {
    static final String STOCK_PACKAGE = "com.cartech.service.canbus";
    static final String DEBUG_ACTIVITY = "com.cartech.service.canbus.activity.DebugActivity";

    static boolean launch(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        try {
            Intent probe = new Intent();
            probe.setComponent(new ComponentName(STOCK_PACKAGE, DEBUG_ACTIVITY));
            ActivityInfo info = context.getPackageManager().getActivityInfo(
                    probe.getComponent(), PackageManager.MATCH_DISABLED_COMPONENTS);

            if (!info.enabled || !info.exported) {
                prefs.edit()
                        .putString("bridge_status", "DebugActivity недоступна: enabled=" +
                                info.enabled + " exported=" + info.exported)
                        .apply();
                return false;
            }

            Intent i = new Intent();
            i.setComponent(new ComponentName(STOCK_PACKAGE, DEBUG_ACTIVITY));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_NO_ANIMATION |
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            context.startActivity(i);

            prefs.edit()
                    .putBoolean("bridge_enabled", true)
                    .putLong("bridge_launch_ms", System.currentTimeMillis())
                    .putString("bridge_status", "Штатный CANBUS Debug запущен под интерфейсом CarInfo")
                    .apply();
            return true;
        } catch (Throwable t) {
            prefs.edit()
                    .putString("bridge_status", "Не удалось запустить DebugActivity: " +
                            t.getClass().getSimpleName() + ": " +
                            (t.getMessage() == null ? "" : t.getMessage()))
                    .apply();
            return false;
        }
    }

    static void disable(Context context) {
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("bridge_enabled", false)
                .putString("bridge_status", "Авто-мост выключен")
                .apply();
    }

    private StockCanbusBridge() {}
}
