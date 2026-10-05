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
    private TextView accessibility;
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

        TextView title = text("PEUGEOT 307 VAN MONITOR  v0.2", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = text(
                "Теперь умеет перехватывать коды 2E ... прямо с экрана штатного CANBUS-приложения.\n" +
                "Сохранение штатного .txt лога больше не обязательно.",
                15, Color.LTGRAY);
        subtitle.setPadding(0, 8, 0, 16);
        root.addView(subtitle);

        accessibility = section(root, "Перехват экрана CANBUS");
        status = section(root, "Фоновый сервис");
        source = section(root, "Дополнительный источник .txt (необязательно)");
        counters = section(root, "Статистика");
        carState = section(root, "Кандидат состояния автомобиля");
        keyState = section(root, "Подрулевые кнопки");
        lastFrame = section(root, "Последний пакет");

        root.addView(button("1. ВКЛЮЧИТЬ ПЕРЕХВАТ CANBUS С ЭКРАНА", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this,
                        "Найдите Peugeot 307 CANBUS screen reader и включите его",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось открыть специальные возможности", Toast.LENGTH_LONG).show();
            }
        }));

        root.addView(button("2. ОЧИСТИТЬ ЗАПИСЬ ПЕРЕД ТЕСТОМ", v -> {
            MonitorService.clearCapture(this);
            prefs.edit().putLong("screen_frames", 0).apply();
            Toast.makeText(this, "Запись очищена. Теперь откройте штатный экран CANBUS.", Toast.LENGTH_SHORT).show();
        }));

        root.addView(button("3. ЭКСПОРТИРОВАТЬ ДИАГНОСТИКУ", v -> exportDiagnostics()));

        autostart = new CheckBox(this);
        autostart.setText("Также запускать обычный фоновый монитор после загрузки Android");
        autostart.setTextColor(Color.WHITE);
        autostart.setTextSize(15);
        autostart.setChecked(prefs.getBoolean("autostart_enabled", false));
        autostart.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean("autostart_enabled", isChecked).apply());
        root.addView(autostart);

        root.addView(button("Выбрать папку с сохранёнными .txt логами (необязательно)", v -> chooseFolder()));
        root.addView(button("Запустить обычный фоновый монитор .txt", v -> startMonitor()));
        root.addView(button("Остановить обычный монитор .txt", v -> stopMonitor()));

        TextView help = text(
                "\nКАК ТЕСТИРОВАТЬ v0.2:\n" +
                "1) Нажмите «Включить перехват CANBUS с экрана».\n" +
                "2) В специальных возможностях включите «Peugeot 307 CANBUS screen reader».\n" +
                "3) Вернитесь сюда и нажмите «Очистить запись перед тестом».\n" +
                "4) Откройте штатное CANBUS-приложение магнитолы, где вживую бегут коды 2E ...\n" +
                "5) Оставьте этот экран открытым во время всего теста. Наша служба работает поверх него в фоне.\n" +
                "6) 5 сек ничего → водительская дверь → закрыть → пассажирская → закрыть → Volume+ → Volume− → кнопка БК.\n" +
                "7) Вернитесь в Peugeot 307 VAN Monitor и нажмите «Экспортировать диагностику».\n\n" +
                "Если счётчик «SCREEN» растёт — прямой перехват работает.\n" +
                "Служба анализирует только текст, содержащий CANBUS-кадры 2E ...; остальной текст экрана в диагностический файл не сохраняется.",
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
            Toast.makeText(this, "Папка логов сохранена", Toast.LENGTH_SHORT).show();
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
            DiagnosticsExporter.export(this);
            Toast.makeText(this,
                    "Готово: Downloads/Peugeot307VanMonitor",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateUi() {
        accessibility.setText(
                prefs.getString("accessibility_status", "ещё не включён") +
                "\nПоследний пакет приложения: " + prefs.getString("screen_package", "—"));

        String s = prefs.getString("service_status", "обычный .txt монитор остановлен");
        long lastActivity = prefs.getLong("last_activity_ms", 0);
        String activity = lastActivity == 0 ? "нет пакетов" :
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(lastActivity));
        status.setText(s + "\nПоследняя активность: " + activity);

        String tree = prefs.getString("log_tree_uri", "");
        String file = prefs.getString("current_file", "");
        source.setText((tree == null || tree.isEmpty() ? "не используется" : tree) +
                (file == null || file.isEmpty() ? "" : "\nФайл: " + file));

        counters.setText(String.format(Locale.US,
                "Изменений/событий: %d   SCREEN: %d   checksum BAD: %d",
                prefs.getLong("frames_total", 0),
                prefs.getLong("screen_frames", 0),
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
