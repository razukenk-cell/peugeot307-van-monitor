package com.razukenk.peugeot307vanmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    public static final String ACTION_STOP = "com.razukenk.peugeot307vanmonitor.STOP";

    private static final String CHANNEL_ID = "peugeot307_van_monitor";
    private static final int NOTIFICATION_ID = 307;
    private static final long MAX_CAPTURE_BYTES = 2L * 1024L * 1024L;

    private SharedPreferences prefs;
    private ScheduledExecutorService executor;
    private String currentFileUri = "";
    private int processedLines = 0;
    private String lastVehiclePayload = "";
    private DirectCarDataReader directReader;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private CarDoorView overlayCar;
    private TextView overlayText;

    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            try {
                updateOverlay();
            } catch (Throwable ignored) {
            }
            mainHandler.postDelayed(this, 250);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Peugeot 307: монитор CANBUS запущен"));
        prefs.edit().putString("service_status", "работает").apply();

        directReader = new DirectCarDataReader(this);
        boolean direct = directReader.start();
        prefs.edit().putString("service_status",
                direct ? "работает; DIRECT CarData подключён" : "работает; DIRECT недоступен, fallback").apply();

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::pollSafely, 0, 650, TimeUnit.MILLISECONDS);

        mainHandler.post(overlayTick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void pollSafely() {
        try {
            if (directReader != null && directReader.isConnected()) {
                directReader.poll();
            }
            poll();
        } catch (Throwable t) {
            prefs.edit()
                    .putString("service_status", "ошибка: " + t.getClass().getSimpleName() + ": " + safeMessage(t))
                    .apply();
        }
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m;
    }

    private void poll() throws Exception {
        String tree = prefs.getString("log_tree_uri", "");
        if (tree == null || tree.isEmpty()) {
            prefs.edit().putString("service_status", "работает; ждёт живой экран CANBUS").apply();
            return;
        }

        Uri treeUri = Uri.parse(tree);
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            prefs.edit().putString("service_status", "работает; нет доступа к папке логов").apply();
            return;
        }

        DocumentFile newest = null;
        long newestTime = Long.MIN_VALUE;
        for (DocumentFile file : root.listFiles()) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".txt")) continue;
            long lm = file.lastModified();
            if (newest == null || lm >= newestTime) {
                newest = file;
                newestTime = lm;
            }
        }

        if (newest == null) {
            prefs.edit().putString("service_status", "работает; ждёт CANBUS").apply();
            return;
        }

        String newestUri = newest.getUri().toString();
        if (!newestUri.equals(currentFileUri)) {
            currentFileUri = newestUri;
            processedLines = 0;
        }

        int lineNo = 0;
        int newFrames = 0;
        InputStream input = getContentResolver().openInputStream(newest.getUri());
        if (input == null) throw new IllegalStateException("openInputStream returned null");

        try (InputStream in = input;
             BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (lineNo <= processedLines) continue;

                FrameParser.Frame frame = FrameParser.parseLine(line);
                if (frame != null) {
                    handleFrame(frame);
                    newFrames++;
                }
            }
        }

        if (lineNo < processedLines) processedLines = 0;
        else processedLines = lineNo;

        SharedPreferences.Editor e = prefs.edit()
                .putString("current_file", newest.getName() == null ? newestUri : newest.getName())
                .putString("service_status", "работает; читает CANBUS");
        if (newFrames > 0) e.putLong("last_activity_ms", System.currentTimeMillis());
        e.apply();
    }

    private void handleFrame(FrameParser.Frame frame) {
        long total = prefs.getLong("frames_total", 0) + 1;
        long bad = prefs.getLong("frames_bad", 0) + (frame.checksumOk ? 0 : 1);

        SharedPreferences.Editor e = prefs.edit()
                .putLong("frames_total", total)
                .putLong("frames_bad", bad)
                .putString("last_frame", frame.hex)
                .putString("last_direction", frame.direction)
                .putString("last_command", String.format(Locale.US, "0x%02X", frame.command));

        boolean capture = false;
        String annotation = "";

        if (frame.command == 0x20 && frame.data.length >= 2) {
            int keyId = frame.unsigned(0);
            int state = frame.unsigned(1);
            e.putString("last_key_id", String.format(Locale.US, "0x%02X", keyId));
            e.putString("last_key_name", VehicleData.keyName(keyId));
            e.putString("last_key_state", keyStateName(state));
            annotation = "KEY " + VehicleData.keyName(keyId) +
                    " id=" + String.format(Locale.US, "0x%02X", keyId) +
                    " state=" + keyStateName(state);
            capture = true;
        } else if (frame.command == 0x01) {
            String payload = frame.payloadHex();
            VehicleData.applyStatusFrame(prefs, e, frame);
            if (!payload.equals(lastVehiclePayload)) {
                annotation = "VEHICLE_STATUS changed doors=" +
                        String.format(Locale.US, "0x%02X", frame.data.length > 10 ? frame.unsigned(10) : 0);
                capture = true;
                lastVehiclePayload = payload;
            }
        } else if (frame.command == 0xC8) {
            VehicleData.applyClockFrame(e, frame);
            annotation = "CLOCK";
            capture = true;
        } else {
            annotation = "CMD=" + String.format(Locale.US, "0x%02X", frame.command);
            capture = true;
        }

        e.apply();

        if (capture) {
            appendCapture(annotation + " | " + frame.direction + " " + frame.hex +
                    " | checksum=" + (frame.checksumOk ? "OK" : "BAD"));
        }
    }

    private String keyStateName(int state) {
        switch (state) {
            case 0x00: return "RELEASED";
            case 0x01: return "PRESSED";
            case 0x02: return "REPEAT/HOLD";
            default: return String.format(Locale.US, "0x%02X", state);
        }
    }

    private void updateOverlay() {
        boolean enabled = prefs.getBoolean("overlay_enabled", false);
        boolean accessibilityOverlay = prefs.getBoolean("accessibility_overlay_active", false);
        boolean permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);

        if (!enabled || accessibilityOverlay || !permitted) {
            removeOverlay();
            return;
        }

        if (overlayView == null) createOverlay();

        int mask = prefs.getInt("door_mask", 0);
        if (overlayCar != null) overlayCar.setDoorMask(mask);

        if (overlayText != null) {
            int keyId = parseHexId(prefs.getString("last_key_id", ""));
            String key = keyId >= 0 ? VehicleData.keyName(keyId) : "—";
            int hour = prefs.getInt("box_hour", -1);
            int minute = prefs.getInt("box_minute", -1);
            String clock = hour >= 0 && minute >= 0
                    ? String.format(Locale.US, "%02d:%02d", hour, minute) : "—";
            overlayText.setText(VehicleData.doorsText(mask) +
                    "\nКнопка: " + key +
                    "\nВремя CAN: " + clock);
            overlayText.setTextColor(mask == 0 ? Color.LTGRAY : Color.rgb(255, 105, 105));
        }
    }

    private int parseHexId(String s) {
        if (s == null) return -1;
        try {
            String x = s.trim().toLowerCase(Locale.US);
            if (x.startsWith("0x")) x = x.substring(2);
            return Integer.parseInt(x, 16);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void createOverlay() {
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8, 6, 8, 6);
        box.setBackgroundColor(Color.argb(225, 15, 17, 20));

        TextView title = new TextView(this);
        title.setText("PEUGEOT 307");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayCar = new CarDoorView(this);
        box.addView(overlayCar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 185));

        overlayText = new TextView(this);
        overlayText.setTextSize(13);
        overlayText.setTextColor(Color.LTGRAY);
        overlayText.setGravity(Gravity.CENTER);
        box.addView(overlayText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                300,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 8;
        lp.y = 70;

        windowManager.addView(box, lp);
        overlayView = box;
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
        }
        overlayView = null;
        overlayCar = null;
        overlayText = null;
    }

    private synchronized void appendCapture(String text) {
        try {
            File capture = new File(getFilesDir(), "capture.log");
            boolean append = capture.exists() && capture.length() < MAX_CAPTURE_BYTES;
            try (FileWriter w = new FileWriter(capture, append)) {
                if (!append) w.write("# Peugeot 307 CarInfo capture v0.6\n");
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                w.write(ts + " " + text + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    static void clearCapture(Context context) {
        File capture = new File(context.getFilesDir(), "capture.log");
        if (capture.exists()) capture.delete();
        File direct = new File(context.getFilesDir(), "direct_car_data.log");
        if (direct.exists()) direct.delete();

        SharedPreferences.Editor e = context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
                .putLong("frames_total", 0)
                .putLong("frames_bad", 0)
                .putLong("screen_frames", 0)
                .remove("last_frame")
                .remove("last_command")
                .remove("last_key_id")
                .remove("last_key_name")
                .remove("last_key_state")
                .remove("vehicle_mask")
                .remove("vehicle_payload")
                .remove("telemetry_last_ms");

        for (int i = 0; i < 13; i++) {
            e.remove("status_b" + i);
            e.remove("current_status_b" + i);
        }
        e.apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Peugeot 307 CarInfo",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Фоновое чтение данных SimpleSoft/RP5");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0, open,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("Peugeot 307 CarInfo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        prefs.edit().putString("service_status", "остановлен").apply();
        if (executor != null) executor.shutdownNow();
        if (directReader != null) directReader.stop();
        mainHandler.removeCallbacks(overlayTick);
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
