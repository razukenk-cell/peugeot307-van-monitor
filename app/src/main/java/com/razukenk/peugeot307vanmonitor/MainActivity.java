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
    private TextView status;
    private TextView source;
    private TextView counters;
    private TextView carState;
    private TextView keyState;
    private TextView lastFrame;
    private CheckBox autostart;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        buildUi();
        requestNotificationPermissionIfNeeded();
        handler.post(refresh);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 18, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 24);
        scroll.addView(root);

        TextView title = text("PEUGEOT 307 VAN MONITOR  v0.1", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = text(
                "Диагностическая версия для RK3566 / CTC01_P01 / SimpleSoft psa_01_sp\n" +
                "Читает .txt-лог штатного CANBUS и фиксирует изменения пакетов 2E ...",
                15, Color.LTGRAY);
        subtitle.setPadding(0, 8, 0, 16);
        root.addView(subtitle);

        status = section(root, "Сервис");
        source = section(root, "Источник CANBUS");
        counters = section(root, "Статистика");
        carState = section(root, "Кандидат состояния автомобиля");
        keyState = section(root, "Подрулевые кнопки");
        lastFrame = section(root, "Последний пакет");

        autostart = new CheckBox(this);
        autostart.setText("Автозапуск после включения магнитолы");
        autostart.setTextColor(Color.WHITE);
        autostart.setTextSize(16);
        autostart.setChecked(prefs.getBoolean("autostart_enabled", false));
        autostart.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("autostart_enabled", isChecked).apply());
        root.addView(autostart);

        root.addView(button("1. ВЫБРАТЬ ПАПКУ С CANBUS .TXT ЛОГАМИ", v -> chooseFolder()));
        root.addView(button("2. ЗАПУСТИТЬ ФОНОВЫЙ МОНИТОР", v -> startMonitor()));
        root.addView(button("ОСТАНОВИТЬ МОНИТОР", v -> stopMonitor()));
        root.addView(button("ОЧИСТИТЬ ЗАПИСЬ ПЕРЕД ТЕСТОМ", v -> {
            MonitorService.clearCapture(this);
            Toast.makeText(this, "Запись очищена. Можно начинать тест.", Toast.LENGTH_SHORT).show();
        }));
        root.addView(button("ЭКСПОРТИРОВАТЬ ДИАГНОСТИКУ", v -> exportDiagnostics()));

        TextView help = text(
                "\nКАК ПРОВЕСТИ ТЕСТ:\n" +
                "• В штатном CANBUS-экране магнитолы включите запись лога, если она включается вручную.\n" +
                "• Нажмите «Очистить запись перед тестом».\n" +
                "• 5 сек ничего → водительская дверь → закрыть → пассажирская дверь → закрыть.\n" +
                "• Volume+ → Volume− → кнопка БК на стрекозе.\n" +
                "• Нажмите «Экспортировать диагностику».\n" +
                "Файл появится в Downloads/Peugeot307VanMonitor — пришлите его в чат.\n\n" +
                "Важно: v0.1 ничего не отправляет в VAN/CAN и не управляет машиной. Она только читает уже созданный штатной магнитолой лог.",
                14, Color.LTGRAY);
        root.addView(help);

        setContentView(scroll);
    }

    private TextView section(LinearLayout root, String label) {
        TextView labelView = text(label, 13, Color.rgb(150, 180, 255));
        labelView.setPadding(0, 10, 0, 2);
        root.addView(labelView);

        TextView value = text("—", 16, Color.WHITE);
        value.setPadding(12, 7, 12, 7);
        value.setBackgroundColor(Color.rgb(35, 35, 35));
        root.addView(value);
        return value;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 0);
        b.setLayoutParams(lp);
        return b;
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
            Toast.makeText(this, "Папка CANBUS-логов сохранена", Toast.LENGTH_SHORT).show();
            startMonitor();
        }
    }

    private void startMonitor() {
        Intent i = new Intent(this, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
            prefs.edit().putString("service_status", "запускается…").apply();
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
            Uri out = DiagnosticsExporter.export(this);
            Toast.makeText(this,
                    "Готово. Файл сохранён в Downloads/Peugeot307VanMonitor",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateUi() {
        String s = prefs.getString("service_status", "остановлен");
        long lastActivity = prefs.getLong("last_activity_ms", 0);
        String activity = lastActivity == 0 ? "нет пакетов" :
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(lastActivity));
        status.setText(s + "\nПоследняя активность: " + activity);

        String tree = prefs.getString("log_tree_uri", "");
        String file = prefs.getString("current_file", "");
        source.setText((tree == null || tree.isEmpty() ? "Папка не выбрана" : tree) +
                (file == null || file.isEmpty() ? "" : "\nТекущий файл: " + file));

        counters.setText(String.format(Locale.US,
                "Пакетов: %d   checksum BAD: %d",
                prefs.getLong("frames_total", 0),
                prefs.getLong("frames_bad", 0)));

        carState.setText("mask candidate: " + prefs.getString("vehicle_mask", "—") +
                "\npayload: " + prefs.getString("vehicle_payload", "—"));

        keyState.setText("ID: " + prefs.getString("last_key_id", "—") +
                "   state: " + prefs.getString("last_key_state", "—"));

        lastFrame.setText(prefs.getString("last_direction", "") + " " +
                prefs.getString("last_frame", "—"));
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
