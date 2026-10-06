package com.razukenk.peugeot307vanmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DiagnosticsExporter {
    static Uri export(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "peugeot307_diagnostic_v09_" + stamp + ".txt";

        StringBuilder report = new StringBuilder();
        report.append("Peugeot 307 CarInfo diagnostic v0.9\n");
        report.append("Generated: ").append(new Date()).append("\n\n");

        report.append("[DEVICE]\n");
        report.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        report.append("Brand: ").append(Build.BRAND).append("\n");
        report.append("Model: ").append(Build.MODEL).append("\n");
        report.append("Device: ").append(Build.DEVICE).append("\n");
        report.append("Product: ").append(Build.PRODUCT).append("\n");
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");

        int mask = p.getInt("door_mask", 0);
        report.append("[VEHICLE]\n");
        report.append(String.format(Locale.US, "Door mask: 0x%02X\n", mask));
        report.append("Doors: ").append(VehicleData.doorsText(mask)).append("\n");
        report.append("Vehicle payload: ").append(p.getString("vehicle_payload", "(none)")).append("\n");
        report.append("Last rich status bytes: ").append(VehicleData.rawStatusBytes(p)).append("\n");
        report.append("Telemetry candidate timestamp: ").append(p.getLong("telemetry_last_ms", 0)).append("\n");
        report.append("B0: ").append(p.getInt("status_b0", -1)).append("\n");
        report.append("B3: ").append(p.getInt("status_b3", -1)).append("\n");
        report.append("B4: ").append(p.getInt("status_b4", -1)).append("\n");
        report.append("B8: ").append(p.getInt("status_b8", -1)).append("\n\n");

        report.append("[AUTO CANBUS BRIDGE]\n");
        report.append("Enabled: ").append(p.getBoolean("bridge_enabled", false)).append("\n");
        report.append("Status: ").append(p.getString("bridge_status", "(none)")).append("\n");
        report.append("Capture: ").append(p.getString("bridge_capture_status", "(none)")).append("\n");
        report.append("Last stock window ms: ").append(p.getLong("bridge_stock_window_ms", 0)).append("\n");
        report.append("Last raw frame ms: ").append(p.getLong("bridge_last_frame_ms", 0)).append("\n");
        report.append("Bridge error: ").append(p.getString("bridge_error", "(none)")).append("\n");
        report.append("Accessibility overlay error: ").append(p.getString("accessibility_overlay_error", "(none)")).append("\n\n");

        report.append("[DIRECT CarData]\n");
        report.append("Status: ").append(p.getString("direct_status", "unknown")).append("\n");
        report.append("Exact API: ").append(p.getBoolean("direct_exact_api", false)).append("\n");
        report.append("Direct door state: ").append(p.getString("direct_door_state_hex", "(none)")).append("\n");
        report.append("Direct hood open: ").append(p.getBoolean("direct_hood_open", false)).append("\n");
        report.append("Direct outside temp: ").append(p.getString("direct_out_temp", "(none)")).append("\n");
        report.append("Direct turn state: ").append(p.getInt("direct_turn_state", -999)).append("\n");
        report.append("Direct CAN-box version: ").append(p.getString("direct_canbox_version", "(none)")).append("\n");
        report.append("Direct current CANBUS: ").append(p.getString("direct_current_canbus", "(none)")).append("\n");
        report.append("Exact error: ").append(p.getString("direct_exact_error", "(none)")).append("\n");
        report.append("Listener: ").append(p.getString("direct_listener", "unknown")).append("\n");
        report.append("Key count: ").append(p.getInt("direct_key_count", 0)).append("\n");
        report.append("Last key: ").append(p.getString("direct_last_key", "(none)")).append("\n");
        report.append("Last value: ").append(p.getString("direct_last_value", "(none)")).append("\n");
        report.append("Snapshot:\n").append(p.getString("direct_snapshot", "(none)")).append("\n\n");

        report.append("[MONITOR]\n");
        report.append("Service: ").append(p.getString("service_status", "unknown")).append("\n");
        report.append("Accessibility: ").append(p.getString("accessibility_status", "unknown")).append("\n");
        report.append("Last source package: ").append(p.getString("screen_package", "(none)")).append("\n");
        report.append("Source folder: ").append(p.getString("log_tree_uri", "(not selected)")).append("\n");
        report.append("Current file: ").append(p.getString("current_file", "(none)")).append("\n");
        report.append("Frames total: ").append(p.getLong("frames_total", 0)).append("\n");
        report.append("Screen frames: ").append(p.getLong("screen_frames", 0)).append("\n");
        report.append("Checksum errors: ").append(p.getLong("frames_bad", 0)).append("\n");
        report.append("Last frame: ").append(p.getString("last_frame", "(none)")).append("\n");
        report.append("Last direction: ").append(p.getString("last_direction", "(none)")).append("\n");
        report.append("Last command: ").append(p.getString("last_command", "(none)")).append("\n");
        report.append("Last key id: ").append(p.getString("last_key_id", "(none)")).append("\n");
        report.append("Last key state: ").append(p.getString("last_key_state", "(none)")).append("\n\n");

        report.append("[CAPTURE - CHANGED/EVENT FRAMES]\n");
        File capture = new File(context.getFilesDir(), "capture.log");
        if (capture.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(capture))) {
                String line;
                while ((line = br.readLine()) != null) report.append(line).append('\n');
            }
        } else {
            report.append("(empty)\n");
        }

        report.append("\n[STOCK ACCESSIBILITY TREE]\n");
        File tree = new File(context.getFilesDir(), "stock_accessibility_tree.log");
        if (tree.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(tree))) {
                String line;
                while ((line = br.readLine()) != null) report.append(line).append('\n');
            }
        } else {
            report.append("(empty)\n");
        }

        report.append("\n[DIRECT CARDATA LOG]\n");
        File direct = new File(context.getFilesDir(), "direct_car_data.log");
        if (direct.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(direct))) {
                String line;
                while ((line = br.readLine()) != null) report.append(line).append('\n');
            }
        } else {
            report.append("(empty)\n");
        }

        byte[] data = report.toString().getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Peugeot307VanMonitor");
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Cannot create Downloads file");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open output");
                out.write(data);
            }
            return uri;
        }

        File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "Peugeot307VanMonitor");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create export directory");
        File outFile = new File(dir, filename);
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            out.write(data);
        }
        return Uri.fromFile(outFile);
    }

    private DiagnosticsExporter() {}
}
