package com.razukenk.peugeot307vanmonitor;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CanScreenAccessibilityService extends AccessibilityService {
    private SharedPreferences prefs;
    private String lastVehiclePayload = "";
    private final Map<Integer, Integer> lastKeyStates = new HashMap<>();
    private String lastOtherFrame = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            try {
                scanActiveWindow();
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        prefs.edit().putString("accessibility_status", "включён").apply();
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) {
            prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        }

        if (event.getPackageName() != null) {
            prefs.edit().putString("screen_package", event.getPackageName().toString()).apply();
        }

        scanActiveWindow();
    }

    private void scanActiveWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        CharSequence pkg = root.getPackageName();
        if (pkg != null) {
            String packageName = pkg.toString();
            prefs.edit().putString("screen_package", packageName).apply();
            if (packageName.equals(getPackageName())) {
                root.recycle();
                return;
            }
        }

        scanNode(root);
        root.recycle();
    }

    private void scanNode(AccessibilityNodeInfo node) {
        if (node == null) return;

        CharSequence text = node.getText();
        if (text != null) scanTextBlock(text.toString());

        CharSequence desc = node.getContentDescription();
        if (desc != null) scanTextBlock(desc.toString());

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                scanNode(child);
                child.recycle();
            }
        }
    }

    private void scanTextBlock(String block) {
        if (block == null || block.indexOf("2E") < 0) return;

        String[] lines = block.split("\\r?\\n");
        for (String line : lines) {
            FrameParser.Frame frame = FrameParser.parseLine(line);
            if (frame != null) recordFrame(frame);
        }

        if (lines.length == 1) {
            int cursor = 0;
            while (cursor < block.length()) {
                int p = block.indexOf("2E ", cursor);
                if (p < 0) break;
                int next = block.indexOf("2E ", p + 3);
                String candidate = next < 0 ? block.substring(p) : block.substring(p, next);
                FrameParser.Frame frame = FrameParser.parseLine(candidate);
                if (frame != null) recordFrame(frame);
                cursor = next < 0 ? block.length() : next;
            }
        }
    }

    private synchronized void recordFrame(FrameParser.Frame frame) {
        boolean meaningful = false;
        String annotation = "";

        if (frame.command == 0x20 && frame.data.length >= 2) {
            int keyId = frame.unsigned(0);
            int state = frame.unsigned(1);
            Integer previous = lastKeyStates.get(keyId);
            if (previous == null || previous != state) {
                lastKeyStates.put(keyId, state);
                meaningful = true;
                annotation = "SCREEN KEY id=" + hex(keyId) + " state=" + keyStateName(state);
            }
        } else if (frame.command == 0x01) {
            String payload = frame.payloadHex();
            if (!payload.equals(lastVehiclePayload)) {
                lastVehiclePayload = payload;
                meaningful = true;
                annotation = "SCREEN VEHICLE_STATUS changed";
            }
        } else if (!frame.hex.equals(lastOtherFrame)) {
            lastOtherFrame = frame.hex;
            meaningful = true;
            annotation = "SCREEN CMD=" + hex(frame.command);
        }

        if (!meaningful) return;

        long total = prefs.getLong("frames_total", 0) + 1;
        long screen = prefs.getLong("screen_frames", 0) + 1;
        long bad = prefs.getLong("frames_bad", 0) + (frame.checksumOk ? 0 : 1);

        SharedPreferences.Editor e = prefs.edit()
                .putLong("frames_total", total)
                .putLong("screen_frames", screen)
                .putLong("frames_bad", bad)
                .putLong("last_activity_ms", System.currentTimeMillis())
                .putString("last_frame", frame.hex)
                .putString("last_direction", "SCREEN")
                .putString("last_command", hex(frame.command))
                .putString("accessibility_status", "включён; живой экран опрашивается");

        if (frame.command == 0x20 && frame.data.length >= 2) {
            e.putString("last_key_id", hex(frame.unsigned(0)));
            e.putString("last_key_state", keyStateName(frame.unsigned(1)));
        }

        if (frame.command == 0x01) {
            VehicleData.applyStatusFrame(prefs, e, frame);
        }

        e.apply();

        appendCapture(annotation + " | SCREEN " + frame.hex +
                " | checksum=" + (frame.checksumOk ? "OK" : "BAD"));
    }

    private String keyStateName(int state) {
        switch (state) {
            case 0x00: return "RELEASED";
            case 0x01: return "PRESSED";
            case 0x02: return "REPEAT/HOLD";
            default: return hex(state);
        }
    }

    private String hex(int value) {
        return String.format(Locale.US, "0x%02X", value & 0xFF);
    }

    private void appendCapture(String text) {
        try {
            File capture = new File(getFilesDir(), "capture.log");
            boolean append = capture.exists() && capture.length() < 2L * 1024L * 1024L;
            try (FileWriter w = new FileWriter(capture, append)) {
                if (!append) w.write("# Peugeot 307 CarInfo capture v0.3\n");
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                w.write(ts + " " + text + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        if (prefs != null) {
            prefs.edit().putString("accessibility_status", "прерван системой").apply();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        if (prefs != null) {
            prefs.edit().putString("accessibility_status", "выключен").apply();
        }
        super.onDestroy();
    }
}
