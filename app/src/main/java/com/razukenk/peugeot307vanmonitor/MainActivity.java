package com.razukenk.peugeot307vanmonitor;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 3071;
    private static final int REQ_NOTIFICATIONS = 3072;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private CarDoorView carView;
    private TextView connection;
    private TextView doorText;
    private TextView bridge;
    private TextView ttyProbe;
    private TextView telemetry;
    private TextView direct;
    private TextView steering;
    private TextView source;
    private TextView raw;
    private TextView accessibility;
    private CheckBox autostart;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 350);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);

        // v0.10 safety reset FIRST: an upgrade from v0.9 may leave bridge_enabled=true.
        // Force AUTO bridge off before starting any app component/UI.
        prefs.edit()
                .putBoolean("bridge_enabled", false)
                .putBoolean("bridge_pending", false)
                .putString("bridge_status", "v0.10 SAFE: AUTO bridge отключён")
                .putString("bridge_capture_status", "v0.10 SAFE: автоклик удалён")
                .apply();

        buildUi();
        requestNotificationPermissionIfNeeded();
        startMonitor(false);
        handler.post(refresh);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 17, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 10, 18, 20);
        scroll.addView(root);

        TextView title = text("PEUGEOT 307 CARINFO  v0.10 SAFE PROBE", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        connection = text("CANBUS: ожидание…", 15, Color.LTGRAY);
        connection.setGravity(Gravity.CENTER_HORIZONTAL);
        connection.setPadding(0, 3, 0, 7);
        root.addView(connection);

        Button probeButton = button("БЕЗОПАСНО ПРОВЕРИТЬ /dev/ttyCanbus", v -> runSafeTtyProbe());
        probeButton.setTextSize(17);
        probeButton.setMinHeight(66);
        root.addView(probeButton);

        TextView safety = text(
                "Только metadata/access probe: stat/lstat/access. Устройство НЕ открывается; read/write не вызываются.",
                13, Color.rgb(170, 220, 170));
        safety.setGravity(Gravity.CENTER_HORIZONTAL);
        safety.setPadding(0, 2, 0, 8);
        root.addView(safety);

        LinearLayout dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(dash);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        dash.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f));

        carView = new CarDoorView(this);
        left.addView(carView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 315));

        doorText = text("Все двери закрыты", 18, Color.WHITE);
        doorText.setGravity(Gravity.CENTER_HORIZONTAL);
        left.addView(doorText);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(12, 0, 0, 0);
        dash.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f));

        ttyProbe = card(right, "/dev/ttyCanbus SAFE probe");
        bridge = card(right, "Stock bridge (v0.10 disabled)");
        direct = card(right, "CarData (диагностика)");
        telemetry = card(right, "Данные автомобиля");
        steering = card(right, "Подрулевой пульт");
        accessibility = card(right, "Accessibility");
        source = card(right, "Fallback");
        raw = card(right, "RAW 0x01");

        autostart = new CheckBox(this);
        autostart.setText("Автозапуск CarInfo");
        autostart.setTextColor(Color.WHITE);
        autostart.setTextSize(15);
        autostart.setChecked(prefs.getBoolean("autostart_enabled", false));
        autostart.setOnCheckedChangeListener((buttonView, enabled) ->
                prefs.edit().putBoolean("autostart_enabled", enabled).apply());
        root.addView(autostart);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row1);

        row1.addView(button("SAFE: bridge выключен", v -> {
            StockCanbusBridge.disable(this);
            Toast.makeText(this, "v0.10 SAFE: stock DebugActivity не запускается", Toast.LENGTH_SHORT).show();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(button("Проверить ttyCanbus", v -> runSafeTtyProbe()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(button("Экспорт диагностики", v -> exportDiagnostics()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);

        row2.addView(button("Папка старых логов", v -> chooseFolder()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(button("Скопировать CANBUS APK", v -> exportCanbusApk()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(button("Очистить диагностику", v -> {
            MonitorService.clearCapture(this);
            Toast.makeText(this, "Диагностическая запись очищена", Toast.LENGTH_SHORT).show();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView help = text(
                "v0.10 — безопасный эксперимент после результата v0.9. AUTO CANBUS bridge полностью отключён: " +
                "CarInfo не запускает штатный DebugActivity, не кликает по его элементам и не перезапускает окно.\n\n" +
                "Кнопка проверки /dev/ttyCanbus выполняет только exists/canRead/canWrite, Os.access и lstat. " +
                "Она НЕ создаёт FileInputStream/FileOutputStream, НЕ вызывает open/read/write/ioctl/termios " +
                "и ничего не отправляет в CAN/VAN. Результат попадёт в экспорт диагностики.",
                13, Color.LTGRAY);
        help.setPadding(0, 8, 0, 2);
        root.addView(help);

        setContentView(scroll);
    }

    private TextView card(LinearLayout parent, String label) {
        TextView l = text(label, 12, Color.rgb(125, 175, 235));
        l.setPadding(0, 4, 0, 1);
        parent.addView(l);

        TextView v = text("—", 14, Color.WHITE);
        v.setPadding(9, 7, 9, 7);
        v.setBackgroundColor(Color.rgb(31, 34, 39));
        parent.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setOnClickListener(listener);
        b.setMinHeight(50);
        return b;
    }

    private void runSafeTtyProbe() {
        if (ttyProbe != null) ttyProbe.setText("Проверяю metadata/access…\nOPEN/READ/WRITE: НЕ выполняются");
        new Thread(() -> {
            DeviceNodeProbe.Result result = DeviceNodeProbe.run(this);
            runOnUiThread(() -> {
                if (ttyProbe != null) ttyProbe.setText(result.summary);
                Toast.makeText(this, "SAFE probe: " + result.verdict, Toast.LENGTH_LONG).show();
            });
        }, "ttyCanbus-safe-probe").start();
    }

    private void startAutoCanbus() {
        StockCanbusBridge.disable(this);
        Toast.makeText(this,
                "v0.10 SAFE: AUTO CANBUS отключён. Используйте безопасную проверку /dev/ttyCanbus.",
                Toast.LENGTH_LONG).show();
    }

    private void launchBridgeNow() {
        StockCanbusBridge.disable(this);
    }

    private void stopAutoCanbus() {
        StockCanbusBridge.disable(this);
        prefs.edit().putBoolean("bridge_pending", false).apply();
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;

            ComponentName mine = new ComponentName(this, CanScreenAccessibilityService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);

            while (splitter.hasNext()) {
                ComponentName component = ComponentName.unflattenFromString(splitter.next());
                if (component != null && component.equals(mine)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this,
                    "Включите «Peugeot 307 CANBUS screen reader»",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "Не удалось открыть Специальные возможности",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.getBoolean("bridge_enabled", false)) {
            StockCanbusBridge.disable(this);
        }
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE &&
                resultCode == RESULT_OK &&
                data != null &&
                data.getData() != null) {

            Uri uri = data.getData();
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }

            prefs.edit().putString("log_tree_uri", uri.toString()).apply();
            Toast.makeText(this, "Папка CANBUS сохранена", Toast.LENGTH_SHORT).show();
            startMonitor(false);
        }
    }

    private void startMonitor(boolean toast) {
        try {
            Intent i = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);

            if (toast) Toast.makeText(this, "Монитор запущен", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "Не удалось запустить монитор: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportDiagnostics() {
        try {
            DiagnosticsExporter.export(this);
            Toast.makeText(this,
                    "Сохранено в Downloads/Peugeot307VanMonitor",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "Ошибка экспорта: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportCanbusApk() {
        Toast.makeText(this,
                "Ищу штатное CANBUS/MCU-приложение…",
                Toast.LENGTH_LONG).show();

        new Thread(() -> {
            try {
                CanbusApkExporter.Result result = CanbusApkExporter.findAndCopy(this);
                runOnUiThread(() -> Toast.makeText(
                        this, result.details, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Ошибка копирования CANBUS APK: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void updateUi() {
        int mask = prefs.getInt("door_mask", 0);
        carView.setDoorMask(mask);
        doorText.setText(VehicleData.doorsText(mask));
        doorText.setTextColor(mask == 0
                ? Color.rgb(170, 220, 170)
                : Color.rgb(255, 105, 105));

        long last = prefs.getLong("last_activity_ms", 0);
        long age = last == 0
                ? Long.MAX_VALUE
                : System.currentTimeMillis() - last;

        if (age < 2500) {
            connection.setText("CANBUS ● данные идут    mask=" +
                    String.format(Locale.US, "0x%02X", mask));
            connection.setTextColor(Color.rgb(110, 230, 130));
        } else if (last > 0) {
            connection.setText("CANBUS ○ последнее " +
                    DateFormat.getTimeInstance(DateFormat.MEDIUM)
                            .format(new Date(last)));
            connection.setTextColor(Color.rgb(240, 190, 90));
        } else {
            connection.setText("SAFE PROBE ○ CANBUS UART не открывается");
            connection.setTextColor(Color.LTGRAY);
        }

        long bridgeFrame = prefs.getLong("bridge_last_frame_ms", 0);
        long bridgeAge = bridgeFrame == 0
                ? Long.MAX_VALUE
                : System.currentTimeMillis() - bridgeFrame;

        if (ttyProbe != null) {
            ttyProbe.setText(DeviceNodeProbe.summary(prefs));
        }

        bridge.setText(
                prefs.getString("bridge_status", "v0.10 SAFE: выключен") +
                "\nCapture: " + prefs.getString("bridge_capture_status", "—") +
                "\nRAW: " + (bridgeAge < 1500 ? "АКТИВЕН" : "нет свежих кадров") +
                "\nОшибка: " + prefs.getString("bridge_error", "—"));

        direct.setText(
                prefs.getString("direct_status", "проверка…") +
                "\nExact door error: " +
                prefs.getString("direct_exact_error", "—") +
                "\nCurrent CANBUS: " +
                prefs.getString("direct_current_canbus", "—"));

        telemetry.setText(VehicleData.telemetrySummary(prefs));

        steering.setText(
                prefs.getString("last_key_name", "—") +
                "\nID: " + prefs.getString("last_key_id", "—") +
                "   " + prefs.getString("last_key_state", "—"));

        accessibility.setText(
                (isAccessibilityServiceEnabled() ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА") +
                "\n" + prefs.getString("accessibility_status", "—") +
                "\nStock package: " +
                prefs.getString("screen_package", "—"));

        source.setText(
                "TXT fallback: " +
                prefs.getString("service_status", "остановлен") +
                "\nФайл: " + prefs.getString("current_file", "—"));

        raw.setText(VehicleData.rawStatusBytes(prefs));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }
}
