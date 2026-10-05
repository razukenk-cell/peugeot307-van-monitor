package com.razukenk.peugeot307vanmonitor;

import android.Manifest;
import android.app.Activity;
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
    private TextView telemetry;
    private TextView direct;
    private TextView steering;
    private TextView source;
    private TextView raw;
    private TextView accessibility;
    private CheckBox autostart;
    private CheckBox overlay;

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

        TextView title = text("PEUGEOT 307 CARINFO  v0.7", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        connection = text("CANBUS: ожидание…", 15, Color.LTGRAY);
        connection.setGravity(Gravity.CENTER_HORIZONTAL);
        connection.setPadding(0, 3, 0, 7);
        root.addView(connection);

        LinearLayout dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(dash);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        dash.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f));

        carView = new CarDoorView(this);
        left.addView(carView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 315));

        doorText = text("Все двери закрыты", 18, Color.WHITE);
        doorText.setGravity(Gravity.CENTER_HORIZONTAL);
        left.addView(doorText);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(12, 0, 0, 0);
        dash.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f));

        direct = card(right, "DIRECT CarData");
        telemetry = card(right, "Данные / кандидаты");
        steering = card(right, "Подрулевой пульт");
        source = card(right, "Источник");
        accessibility = card(right, "Живой CANBUS-экран");
        raw = card(right, "RAW 0x01");

        overlay = new CheckBox(this);
        overlay.setText("Плавающая машина поверх штатного CANBUS-лога");
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(15);
        overlay.setChecked(prefs.getBoolean("overlay_enabled", false));
        overlay.setOnCheckedChangeListener((buttonView, enabled) -> {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                prefs.edit().putBoolean("overlay_enabled", false).apply();
                buttonView.setChecked(false);
                requestOverlayPermission();
                return;
            }
            prefs.edit().putBoolean("overlay_enabled", enabled).apply();
            startMonitor(false);
        });
        root.addView(overlay);

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

        row1.addView(button("Чтение экрана", v -> openAccessibility()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(button("Разрешить поверх окон", v -> requestOverlayPermission()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(button("Папка логов", v -> chooseFolder()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);

        row2.addView(button("Экспорт лога", v -> exportDiagnostics()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(button("НАЙТИ И СКОПИРОВАТЬ CANBUS APK", v -> exportCanbusApk()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f));
        row2.addView(button("Очистить", v -> {
            MonitorService.clearCapture(this);
            Toast.makeText(this, "Запись очищена", Toast.LENGTH_SHORT).show();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView help = text(
                "v0.7 читает точные системные ключи штатного CANBUS: state.canbus.door_state.i и state.canbus.out_temp.s. " +
                "Если DIRECT станет «ПОДКЛЮЧЕНО», штатный CANBUS-лог открывать больше не нужно.\n" +
                "Плавающее окно теперь создаётся через Accessibility overlay, чтобы быть выше штатного окна лога. " +
                "Никаких команд в VAN/CAN приложение не передаёт.",
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

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Включите «Peugeot 307 CANBUS screen reader»", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть специальные возможности", Toast.LENGTH_LONG).show();
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("overlay_enabled", true).apply();
            overlay.setChecked(true);
            startMonitor(false);
            Toast.makeText(this, "Плавающее окно включено", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            prefs.edit().putBoolean("overlay_pending", true).apply();
            startActivity(intent);
            Toast.makeText(this, "Разрешите «Показывать поверх других приложений», затем вернитесь", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть разрешение поверх окон", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            if (prefs.getBoolean("overlay_pending", false)) {
                prefs.edit().putBoolean("overlay_pending", false).putBoolean("overlay_enabled", true).apply();
            }
            if (prefs.getBoolean("overlay_enabled", false) && overlay != null) {
                overlay.setChecked(true);
                startMonitor(false);
            }
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
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
            Toast.makeText(this, "Не удалось запустить: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportDiagnostics() {
        try {
            DiagnosticsExporter.export(this);
            Toast.makeText(this, "Сохранено в Downloads/Peugeot307VanMonitor", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void exportCanbusApk() {
        Toast.makeText(this, "Ищу штатное CANBUS/MCU-приложение…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                CanbusApkExporter.Result result = CanbusApkExporter.findAndCopy(this);
                runOnUiThread(() -> Toast.makeText(this,
                        result.details,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Ошибка копирования CANBUS APK: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void exportSystemProbe() {
        Toast.makeText(this, "Собираю системную диагностику и доступные CANBUS APK…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                SystemProbeExporter.export(this);
                runOnUiThread(() -> Toast.makeText(this,
                        "Готово: Downloads/Peugeot307VanMonitor/peugeot307_system_probe_....zip",
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Ошибка probe: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void updateUi() {
        int mask = prefs.getInt("door_mask", 0);
        carView.setDoorMask(mask);
        doorText.setText(VehicleData.doorsText(mask));
        doorText.setTextColor(mask == 0 ? Color.rgb(170, 220, 170) : Color.rgb(255, 105, 105));

        long last = prefs.getLong("last_activity_ms", 0);
        long age = last == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - last;
        if (age < 2500) {
            connection.setText("CANBUS ● данные идут    mask=" + String.format(Locale.US, "0x%02X", mask));
            connection.setTextColor(Color.rgb(110, 230, 130));
        } else if (last > 0) {
            connection.setText("CANBUS ○ последнее " +
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(last)));
            connection.setTextColor(Color.rgb(240, 190, 90));
        } else {
            connection.setText("CANBUS ○ ждём штатный лог/экран");
            connection.setTextColor(Color.LTGRAY);
        }

        direct.setText(
                prefs.getString("direct_status", "проверка…") +
                "\nExact API: " + (prefs.getBoolean("direct_exact_api", false) ? "OK" : "нет") +
                "\nДвери direct: " + prefs.getString("direct_door_state_hex", "—") +
                (prefs.getBoolean("direct_hood_open", false) ? "  КАПОТ ОТКРЫТ" : "") +
                "\nНаружная t° direct: " + prefs.getString("direct_out_temp", "—") +
                "\nCAN-box: " + prefs.getString("direct_canbox_version", "—") +
                "\nListener: " + prefs.getString("direct_listener", "—") +
                "\nПоследнее: " + prefs.getString("direct_last_key", "—") +
                " = " + prefs.getString("direct_last_value", "—"));

        telemetry.setText(VehicleData.telemetrySummary(prefs));

        steering.setText(
                prefs.getString("last_key_name", "—") +
                "\nID: " + prefs.getString("last_key_id", "—") +
                "   " + prefs.getString("last_key_state", "—"));

        source.setText(
                prefs.getString("service_status", "остановлен") +
                "\nФайл: " + prefs.getString("current_file", "—"));

        accessibility.setText(
                prefs.getString("accessibility_status", "не включён") +
                "\nПакет CANBUS: " + prefs.getString("screen_package", "—") +
                "\nSCREEN: " + prefs.getLong("screen_frames", 0));

        raw.setText(VehicleData.rawStatusBytes(prefs));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }
}
