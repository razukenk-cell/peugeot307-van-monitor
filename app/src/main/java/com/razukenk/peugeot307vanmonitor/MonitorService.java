package com.razukenk.peugeot307vanmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
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

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Ожидание папки CANBUS-логов"));
        prefs.edit().putString("service_status", "работает").apply();

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::pollSafely, 0, 1200, TimeUnit.MILLISECONDS);
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
            poll();
        } catch (Throwable t) {
            prefs.edit().putString("service_status", "ошибка: " + t.getClass().getSimpleName() + ": " + safeMessage(t)).apply();
        }
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m;
    }

    private void poll() throws Exception {
        String tree = prefs.getString("log_tree_uri", "");
        if (tree == null || tree.isEmpty()) {
            prefs.edit().putString("service_status", "работает, папка логов не выбрана").apply();
            return;
        }

        Uri treeUri = Uri.parse(tree);
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            prefs.edit().putString("service_status", "нет доступа к выбранной папке").apply();
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
            prefs.edit().putString("service_status", "работает, .txt логов в папке пока нет").apply();
            return;
        }

        String newestUri = newest.getUri().toString();
        if (!newestUri.equals(currentFileUri)) {
            currentFileUri = newestUri;
            processedLines = 0;
        }

        int lineNo = 0;
        int newFrames = 0;
        try (InputStream in = getContentResolver().openInputStream(newest.getUri());
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
        processedLines = lineNo;

        SharedPreferences.Editor e = prefs.edit()
                .putString("current_file", newest.getName() == null ? newestUri : newest.getName())
                .putString("service_status", "работает, слежение активно");
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
            e.putString("last_key_state", keyStateName(state));
            annotation = "KEY id=" + String.format(Locale.US, "0x%02X", keyId) + " state=" + keyStateName(state);
            capture = true;
        } else if (frame.command == 0x01) {
            String payload = frame.payloadHex();
            e.putString("vehicle_payload", payload);
            if (frame.data.length > 10) {
                e.putString("vehicle_mask", String.format(Locale.US, "0x%02X", frame.unsigned(10)));
            }
            if (!payload.equals(lastVehiclePayload)) {
                annotation = "VEHICLE_STATUS changed";
                capture = true;
                lastVehiclePayload = payload;
            }
        } else {
            annotation = "CMD=" + String.format(Locale.US, "0x%02X", frame.command);
            capture = true;
        }

        e.apply();

        if (capture) {
            appendCapture(annotation + " | " + frame.direction + " " + frame.hex + " | checksum=" + (frame.checksumOk ? "OK" : "BAD"));
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

    private synchronized void appendCapture(String text) {
        try {
            File capture = new File(getFilesDir(), "capture.log");
            boolean append = capture.exists() && capture.length() < MAX_CAPTURE_BYTES;
            try (FileWriter w = new FileWriter(capture, append)) {
                if (!append) {
                    w.write("# Peugeot 307 VAN Monitor capture\n");
                }
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                w.write(ts + " " + text + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    static void clearCapture(Context context) {
        File capture = new File(context.getFilesDir(), "capture.log");
        if (capture.exists()) capture.delete();
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
                .putLong("frames_total", 0)
                .putLong("frames_bad", 0)
                .remove("last_frame")
                .remove("last_command")
                .remove("last_key_id")
                .remove("last_key_state")
                .remove("vehicle_mask")
                .remove("vehicle_payload")
                .apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Peugeot 307 VAN Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Фоновое чтение CANBUS/VAN логов");
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

        return b.setContentTitle("Peugeot 307 VAN Monitor")
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
