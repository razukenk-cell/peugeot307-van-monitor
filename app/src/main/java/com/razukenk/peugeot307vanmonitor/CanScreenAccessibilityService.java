package com.razukenk.peugeot307vanmonitor;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CanScreenAccessibilityService extends AccessibilityService {
    private static final long AUTO_CAPTURE_DELAY_MS = 1300;
    private static final long RELAUNCH_DELAY_MS = 4500;

    private SharedPreferences prefs;
    private String lastVehiclePayload = "";
    private final Map<Integer, Integer> lastKeyStates = new HashMap<>();
    private String lastOtherFrame = "";

    private long lastFrameSeenMs = 0;
    private long lastStockWindowSeenMs = 0;
    private long lastBridgeLaunchAttemptMs = 0;
    private long lastCaptureAttemptMs = 0;
    private long lastTreeDumpMs = 0;

    private WindowManager windowManager;
    private View overlayView;
    private CarDoorView overlayCar;
    private TextView overlayStatus;
    private TextView overlayDoors;
    private TextView overlayData;
    private TextView overlayKey;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            try {
                scanCanbusWindows();
                updateBridgeOverlay();
            } catch (Throwable t) {
                if (prefs != null) {
                    prefs.edit().putString("bridge_error",
                            t.getClass().getSimpleName() + ": " +
                                    (t.getMessage() == null ? "" : t.getMessage())).apply();
                }
            }
            handler.postDelayed(this, 120);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("accessibility_status", "включён; v0.10 PASSIVE ONLY")
                .putBoolean("accessibility_overlay_active", false)
                .putBoolean("bridge_enabled", false)
                .putBoolean("bridge_pending", false)
                .putString("bridge_status", "v0.10 SAFE: Accessibility только наблюдает, без кликов")
                .putString("bridge_capture_status", "v0.10 SAFE: ACTION_CLICK запрещён")
                .apply();
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) prefs = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        if (event.getPackageName() != null) {
            rememberExternalPackage(event.getPackageName().toString());
        }
        scanCanbusWindows();
    }

    private void rememberExternalPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (packageName.equals(getPackageName())) return;
        if (packageName.equals("com.android.systemui")) return;
        prefs.edit().putString("screen_package", packageName).apply();
    }

    private void scanCanbusWindows() {
        boolean stockFound = false;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        if (root == null) continue;
                        CharSequence pkg = root.getPackageName();
                        if (pkg == null) continue;

                        String packageName = pkg.toString();
                        rememberExternalPackage(packageName);

                        if (StockCanbusBridge.STOCK_PACKAGE.equals(packageName)) {
                            stockFound = true;
                            lastStockWindowSeenMs = System.currentTimeMillis();
                            scanNode(root);
                        }
                    } finally {
                        if (root != null) root.recycle();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback for firmwares which don't expose interactive-window enumeration.
        if (!stockFound) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                try {
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null) {
                        String packageName = pkg.toString();
                        rememberExternalPackage(packageName);
                        if (StockCanbusBridge.STOCK_PACKAGE.equals(packageName)) {
                            lastStockWindowSeenMs = System.currentTimeMillis();
                            scanNode(root);
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
        }

        prefs.edit()
                .putLong("bridge_stock_window_ms", lastStockWindowSeenMs)
                .putLong("bridge_last_frame_ms", lastFrameSeenMs)
                .apply();
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
        boolean found = false;

        for (String line : lines) {
            FrameParser.Frame frame = FrameParser.parseLine(line);
            if (frame != null) {
                found = true;
                recordFrame(frame);
            }
        }

        if (lines.length == 1) {
            int cursor = 0;
            while (cursor < block.length()) {
                int p = block.indexOf("2E ", cursor);
                if (p < 0) break;
                int next = block.indexOf("2E ", p + 3);
                String candidate = next < 0 ? block.substring(p) : block.substring(p, next);
                FrameParser.Frame frame = FrameParser.parseLine(candidate);
                if (frame != null) {
                    found = true;
                    recordFrame(frame);
                }
                cursor = next < 0 ? block.length() : next;
            }
        }

        if (found) {
            lastFrameSeenMs = System.currentTimeMillis();
            prefs.edit()
                    .putString("bridge_status", "RAW поток получен из штатного CANBUS процесса")
                    .apply();
        }
    }

    private void tryEnableCapture(AccessibilityNodeInfo root) {
        // v0.10 SAFETY: deliberately no ACTION_CLICK of any Accessibility node.
        if (prefs != null) {
            prefs.edit().putString("bridge_capture_status",
                    "v0.10 SAFE: автоклик отключён; ACTION_CLICK не выполняется").apply();
        }
    }

    private AccessibilityNodeInfo findBestCaptureToggle(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // Compose Switch обычно не содержит текст "Capture", зато публикуется как
        // checkable/clickable accessibility node. В штатном DebugActivity это
        // первый и основной toggle после надписи "Capture:".
        AccessibilityNodeInfo checkable = findFirstCheckable(root);
        if (checkable != null) return checkable;

        AccessibilityNodeInfo label = findTextNode(root, "capture");
        if (label != null) {
            try {
                AccessibilityNodeInfo parent = label.getParent();
                for (int level = 0; level < 5 && parent != null; level++) {
                    AccessibilityNodeInfo inside = findFirstInteractive(parent, label);
                    if (inside != null) {
                        if (parent != label) parent.recycle();
                        return inside;
                    }

                    AccessibilityNodeInfo next = parent.getParent();
                    parent.recycle();
                    parent = next;
                }

                if (label.isClickable()) return AccessibilityNodeInfo.obtain(label);
            } finally {
                label.recycle();
            }
        }

        return findFirstInteractive(root, null);
    }

    private AccessibilityNodeInfo findFirstCheckable(AccessibilityNodeInfo node) {
        if (node == null) return null;

        CharSequence cls = node.getClassName();
        String className = cls == null ? "" : cls.toString().toLowerCase(Locale.US);

        if ((node.isCheckable() || className.contains("switch") || className.contains("checkbox"))
                && node.isEnabled()) {
            return AccessibilityNodeInfo.obtain(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFirstCheckable(child);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;

        StringBuilder joined = new StringBuilder();
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null) joined.append(t);
        if (d != null) joined.append(' ').append(d);

        if (joined.toString().toLowerCase(Locale.US).contains(needle)) {
            return AccessibilityNodeInfo.obtain(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findTextNode(child, needle);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstInteractive(
            AccessibilityNodeInfo node, AccessibilityNodeInfo exclude) {
        if (node == null) return null;

        boolean excluded = exclude != null && node.equals(exclude);
        if (!excluded && node.isEnabled() && (node.isCheckable() || node.isClickable())) {
            return AccessibilityNodeInfo.obtain(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFirstInteractive(child, exclude);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private String describeNode(AccessibilityNodeInfo node) {
        if (node == null) return "null";
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        return "class=" + String.valueOf(node.getClassName()) +
                " text=" + String.valueOf(node.getText()) +
                " desc=" + String.valueOf(node.getContentDescription()) +
                " clickable=" + node.isClickable() +
                " checkable=" + node.isCheckable() +
                " checked=" + node.isChecked() +
                " bounds=" + r.toShortString();
    }

    private void dumpAccessibilityTree(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastTreeDumpMs < 3000) return;
        lastTreeDumpMs = now;

        try {
            File file = new File(getFilesDir(), "stock_accessibility_tree.log");
            try (FileWriter w = new FileWriter(file, false)) {
                w.write("# Peugeot 307 stock CANBUS accessibility tree v0.10 SAFE\n");
                dumpNode(w, root, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void dumpNode(FileWriter w, AccessibilityNodeInfo node, int depth) throws Exception {
        if (node == null || depth > 30) return;

        Rect r = new Rect();
        node.getBoundsInScreen(r);

        for (int i = 0; i < depth; i++) w.write("  ");
        w.write(describeNode(node) +
                " children=" + node.getChildCount() + "\n");

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    dumpNode(w, child, depth + 1);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    private void maintainBridge() {
        // v0.10 SAFETY: no automatic launch or re-launch of stock DebugActivity.
        if (prefs != null && prefs.getBoolean("bridge_enabled", false)) {
            StockCanbusBridge.disable(this);
        }
    }

    private synchronized void recordFrame(FrameParser.Frame frame) {
        lastFrameSeenMs = System.currentTimeMillis();

        boolean meaningful = false;
        String annotation = "";

        if (frame.command == 0x20 && frame.data.length >= 2) {
            int keyId = frame.unsigned(0);
            int state = frame.unsigned(1);
            Integer previous = lastKeyStates.get(keyId);
            if (previous == null || previous != state) {
                lastKeyStates.put(keyId, state);
                meaningful = true;
                annotation = "BRIDGE KEY " + VehicleData.keyName(keyId) +
                        " id=" + hex(keyId) + " state=" + keyStateName(state);
            }
        } else if (frame.command == 0x01) {
            String payload = frame.payloadHex();
            if (!payload.equals(lastVehiclePayload)) {
                lastVehiclePayload = payload;
                meaningful = true;
                annotation = "BRIDGE VEHICLE_STATUS changed";
            }
        } else if (frame.command == 0xC8) {
            meaningful = true;
            annotation = "BRIDGE CLOCK";
        } else if (!frame.hex.equals(lastOtherFrame)) {
            lastOtherFrame = frame.hex;
            meaningful = true;
            annotation = "BRIDGE CMD=" + hex(frame.command);
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
                .putString("last_direction", "BRIDGE")
                .putString("last_command", hex(frame.command))
                .putString("accessibility_status", "CANBUS bridge активен");

        if (frame.command == 0x20 && frame.data.length >= 2) {
            int keyId = frame.unsigned(0);
            e.putString("last_key_id", hex(keyId));
            e.putString("last_key_name", VehicleData.keyName(keyId));
            e.putString("last_key_state", keyStateName(frame.unsigned(1)));
        }

        if (frame.command == 0x01) VehicleData.applyStatusFrame(prefs, e, frame);
        if (frame.command == 0xC8) VehicleData.applyClockFrame(e, frame);

        e.apply();

        appendCapture(annotation + " | BRIDGE " + frame.hex +
                " | checksum=" + (frame.checksumOk ? "OK" : "BAD"));
    }

    private void updateBridgeOverlay() {
        if (prefs == null) return;

        boolean enabled = prefs.getBoolean("bridge_enabled", false);
        if (!enabled) {
            removeOverlay();
            prefs.edit().putBoolean("accessibility_overlay_active", false).apply();
            return;
        }

        long age = lastFrameSeenMs == 0 ? Long.MAX_VALUE :
                System.currentTimeMillis() - lastFrameSeenMs;

        // Пока Capture ещё не включён, НЕ закрываем штатный DebugActivity.
        // Это даёт Accessibility доступ к Compose Switch и одновременно
        // оставляет ручной fallback: если автоклик не сработает, пользователь
        // увидит штатный экран и сможет один раз переключить Capture.
        if (age >= 1800) {
            removeOverlay();
            prefs.edit().putBoolean("accessibility_overlay_active", false).apply();
            return;
        }

        if (overlayView == null) {
            try {
                createFullScreenOverlay();
                prefs.edit().putBoolean("accessibility_overlay_active", true).apply();
            } catch (Throwable t) {
                prefs.edit()
                        .putBoolean("accessibility_overlay_active", false)
                        .putString("accessibility_overlay_error",
                                t.getClass().getSimpleName() + ": " +
                                        (t.getMessage() == null ? "" : t.getMessage()))
                        .apply();
                return;
            }
        }

        int mask = prefs.getInt("door_mask", 0);
        if (overlayCar != null) overlayCar.setDoorMask(mask);

        if (overlayStatus != null) {
            String status = age < 1200
                    ? "CANBUS ● RAW поток активен"
                    : "CANBUS ○ запускаю штатный декодер…";
            overlayStatus.setText(status);
            overlayStatus.setTextColor(age < 1200
                    ? Color.rgb(110, 230, 130)
                    : Color.rgb(240, 190, 90));
        }

        if (overlayDoors != null) {
            overlayDoors.setText(VehicleData.doorsText(mask));
            overlayDoors.setTextColor(mask == 0
                    ? Color.rgb(170, 220, 170)
                    : Color.rgb(255, 105, 105));
        }

        if (overlayData != null) {
            int range = prefs.getInt("range_candidate", -1);
            int outside = prefs.getInt("outside_candidate", -1);
            int hour = prefs.getInt("box_hour", -1);
            int minute = prefs.getInt("box_minute", -1);

            StringBuilder s = new StringBuilder();
            s.append("Запас хода: ");
            s.append(range < 0 ? "—" : range + " км");
            s.append("\nНаружная t°: ");
            s.append(outside < 0 || outside == 0xFF ? "—" : outside + " °C");
            s.append("\nВремя CAN: ");
            s.append(hour >= 0 && minute >= 0
                    ? String.format(Locale.US, "%02d:%02d", hour, minute)
                    : "—");
            s.append("\nКадров: ").append(prefs.getLong("screen_frames", 0));
            overlayData.setText(s.toString());
        }

        if (overlayKey != null) {
            overlayKey.setText("Последняя кнопка: " +
                    prefs.getString("last_key_name", "—") +
                    "  " + prefs.getString("last_key_state", ""));
        }
    }

    private void createFullScreenOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 12, 24, 18);
        root.setBackgroundColor(Color.rgb(12, 14, 18));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("PEUGEOT 307 CARINFO  v0.10 SAFE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        top.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button exit = new Button(this);
        exit.setText("ВЫЙТИ");
        exit.setTextSize(12);
        exit.setOnClickListener(v -> exitBridgeMode());
        top.addView(exit, new LinearLayout.LayoutParams(
                130, LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayStatus = new TextView(this);
        overlayStatus.setText("CANBUS: запуск…");
        overlayStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayStatus.setTextSize(16);
        overlayStatus.setTextColor(Color.LTGRAY);
        overlayStatus.setPadding(0, 3, 0, 8);
        root.addView(overlayStatus);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        body.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1.12f));

        overlayCar = new CarDoorView(this);
        left.addView(overlayCar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        overlayDoors = new TextView(this);
        overlayDoors.setText("Все двери закрыты");
        overlayDoors.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayDoors.setTextSize(19);
        overlayDoors.setTextColor(Color.rgb(170, 220, 170));
        overlayDoors.setPadding(0, 4, 0, 6);
        left.addView(overlayDoors);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(18, 10, 0, 0);
        body.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.88f));

        TextView source = new TextView(this);
        source.setText("Источник: штатный com.cartech.service.canbus\nDebugActivity работает скрыто под этим экраном.");
        source.setTextSize(14);
        source.setTextColor(Color.rgb(130, 180, 240));
        source.setPadding(10, 10, 10, 14);
        source.setBackgroundColor(Color.rgb(28, 31, 37));
        right.addView(source);

        overlayData = new TextView(this);
        overlayData.setText("Запас хода: —\nНаружная t°: —\nВремя CAN: —");
        overlayData.setTextSize(18);
        overlayData.setTextColor(Color.WHITE);
        overlayData.setPadding(10, 16, 10, 16);
        right.addView(overlayData);

        overlayKey = new TextView(this);
        overlayKey.setText("Последняя кнопка: —");
        overlayKey.setTextSize(16);
        overlayKey.setTextColor(Color.LTGRAY);
        overlayKey.setPadding(10, 8, 10, 8);
        right.addView(overlayKey);

        TextView note = new TextView(this);
        note.setText("Это безопасный read-only мост: штатный CANBUS процесс продолжает владеть /dev/ttyCanbus, CarInfo только читает его Debug UI через Accessibility.");
        note.setTextSize(12);
        note.setTextColor(Color.rgb(150, 155, 165));
        note.setPadding(10, 14, 10, 8);
        right.addView(note);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(root, lp);
        overlayView = root;
    }

    private void exitBridgeMode() {
        StockCanbusBridge.disable(this);
        removeOverlay();
        prefs.edit().putBoolean("accessibility_overlay_active", false).apply();

        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable ignored) {
        }

        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
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
        overlayStatus = null;
        overlayDoors = null;
        overlayData = null;
        overlayKey = null;
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
                if (!append) w.write("# Peugeot 307 CarInfo bridge capture v0.10 SAFE\n");
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date());
                w.write(ts + " " + text + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        if (prefs != null) prefs.edit().putString("accessibility_status", "прерван системой").apply();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        removeOverlay();
        if (prefs != null) {
            prefs.edit()
                    .putString("accessibility_status", "выключен")
                    .putBoolean("accessibility_overlay_active", false)
                    .apply();
        }
        super.onDestroy();
    }
}
