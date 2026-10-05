package com.razukenk.peugeot307vanmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("autostart_enabled", false)) return;

        Intent service = new Intent(context, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            prefs.edit()
                    .putString("service_status", "Автозапуск не удался: " + e.getClass().getSimpleName())
                    .apply();
        }
    }
}
