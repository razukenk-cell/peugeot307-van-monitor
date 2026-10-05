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
        buildUi();
        requestNotificationPermissionIfNeeded();

        String tree = prefs.getString("log_tree_uri", "");
        if (tree != null && !tree.isEmpty()) startMonitor(false);

        handler.post(refresh);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 17, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 12, 18, 22);
        scroll.addView(root);

        TextView title = text("PEUGEOT 307 CARINFO  v0.3", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        connection = text("CANBUS: ожидание…", 15, Color.LTGRAY);
        connection.setGravity(Gravity.CENTER_HORIZONTAL);
        connection.setPadding(0, 4, 0, 8);
        root.addView(connection);

        LinearLayout dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.HORIZONTAL);
        dash.setGravity(Gravity.TOP);
        root.addView(dash, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        dash.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.18f));

        carView = new CarDoorView(this);
        left.addView(carView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 330));

        doorText = text("Все двери закрыты", 18, Color.WHITE);
        doorText.setGravity(Gravity.CENTER_HORIZONTAL);
        doorText.setPadding(4, 8, 4, 8);
        left.addView(doorText);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(14, 0, 0, 0);
        dash.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.82f));

        telemetry = card(right, "Данные автомобиля");
        steering = card(right, "Подрулевой пульт");
        source = card(right, "Источник данных");
        accessibility = card(right, "Живой экран");
        raw = card(right, "RAW статус");

        TextView note = text(
                "Двери уже расшифрованы по вашему тесту: 08 багажник, 10 ЛЗ, 20 ПЗ, 40 водительская, 80 передняя пассажирская.",
                13, Color.rgb(160, 170, 180));
        note.setPadding(2, 8, 2, 10);
        root.addView(note);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(controls);

        controls.addView(button("Включить чтение экрана", v -> openAccessibility()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(button("Выбрать папку логов", v -> chooseFolder()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(button("Экспорт диагностики", v -> exportDiagnostics()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        autostart = new CheckBox(this);
        autostart.setText("Автозапуск CarInfo после включения магнитолы");
        autostart.setTextColor(Color.WHITE);
        autostart.setTextSize(15);
        autostart.setChecked(prefs.getBoolean("autostart_enabled", false));
        autostart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("autostart_enabled", isChecked).apply();
            if (isChecked) startMonitor(false);
        });
        root.addView(autostart);

        LinearLayout testControls = new LinearLayout(this);
        testControls.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(testControls);

        testControls.addView(button("Очистить тестовую запись", v -> {
            MonitorService.clearCapture(this);
            Toast.makeText(this, "Диагностическая запись очищена", Toast.LENGTH_SHORT).show();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        testControls.addView(button("Запустить монитор", v -> startMonitor(true)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        testControls.addView(button("Остановить монитор", v -> stopMonitor()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView help = text(
                "Для дверей приложение уже умеет рисовать своё отображение — штатная графика магнитолы не нужна. " +
                "Пока источник — поток RP5 из штатного CANBUS-лога или Accessibility. " +
                "Температуру, расход, топливо и запас хода будем добавлять только после точного подтверждения байтов: v0.3 уже сохраняет кандидаты B0/B3/B4/B8.",
                13, Color.LTGRAY);
        help.setPadding(0, 10, 0, 4);
        root.addView(help);

        setContentView(scroll);
    }

    private TextView card(LinearLayout parent, String label) {
        TextView l = text(label, 12, Color.rgb(125, 175, 235));
        l.setPadding(0, 5, 0, 2);
        parent.addView(l);

        TextView v = text("—", 15, Color.WHITE);
        v.setPadding(10, 8, 10, 8);
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
        b.setTextSize(13);
        b.setOnClickListener(listener);
        b.setMinHeight(52);
        return b;
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this,
                    "Включите «Peugeot 307 CANBUS screen reader»",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть специальные возможности", Toast.LENGTH_LONG).show();
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

    private void stopMonitor() {
        stopService(new Intent(this, MonitorService.class));
        prefs.edit().putString("service_status", "остановлен").apply();
    }

    private void exportDiagnostics() {
        try {
            DiagnosticsExporter.export(this);
            Toast.makeText(this,
                    "Сохранено: Downloads/Peugeot307VanMonitor",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateUi() {
        int mask = prefs.getInt("door_mask", 0);
        carView.setDoorMask(mask);
        doorText.setText(VehicleData.doorsText(mask));
        doorText.setTextColor(mask == 0 ? Color.rgb(170, 220, 170) : Color.rgb(255, 105, 105));

        long last = prefs.getLong("last_activity_ms", 0);
        long age = last == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - last;
        String conn;
        if (age < 2500) {
            conn = "CANBUS ● данные идут";
            connection.setTextColor(Color.rgb(110, 230, 130));
        } else if (last > 0) {
            conn = "CANBUS ○ последнее сообщение " +
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(last));
            connection.setTextColor(Color.rgb(240, 190, 90));
        } else {
            conn = "CANBUS ○ ждём данные";
            connection.setTextColor(Color.LTGRAY);
        }
        connection.setText(conn + "    mask=" + String.format(Locale.US, "0x%02X", mask));

        telemetry.setText(VehicleData.telemetrySummary(prefs));

        steering.setText(
                "ID: " + prefs.getString("last_key_id", "—") +
                "\nСостояние: " + prefs.getString("last_key_state", "—"));

        String file = prefs.getString("current_file", "");
        String service = prefs.getString("service_status", "остановлен");
        source.setText(service +
                (file == null || file.isEmpty() ? "\nФайл: —" : "\nФайл: " + file));

        accessibility.setText(
                prefs.getString("accessibility_status", "не включён") +
                "\nSCREEN кадров: " + prefs.getLong("screen_frames", 0) +
                "\nПакет: " + prefs.getString("screen_package", "—"));

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
