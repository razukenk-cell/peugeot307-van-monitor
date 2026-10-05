package com.razukenk.peugeot307vanmonitor;

import android.content.SharedPreferences;

import java.util.Locale;

final class VehicleData {
    static final int BOOT = 0x08;
    static final int REAR_LEFT = 0x10;
    static final int REAR_RIGHT = 0x20;
    static final int FRONT_LEFT = 0x40;
    static final int FRONT_RIGHT = 0x80;

    static void applyStatusFrame(SharedPreferences prefs, SharedPreferences.Editor e, FrameParser.Frame frame) {
        if (frame.command != 0x01 || frame.data.length < 13) return;

        int mask = frame.unsigned(10);
        e.putInt("door_mask", mask);
        e.putString("vehicle_mask", String.format(Locale.US, "0x%02X", mask));
        e.putString("vehicle_payload", frame.payloadHex());

        // Keep the last complete "awake/engine-on" status sample.
        // In the observed RP5 stream byte 8 becomes 0xFF when the richer telemetry disappears.
        if (frame.unsigned(8) != 0xFF) {
            e.putLong("telemetry_last_ms", System.currentTimeMillis());
            for (int i = 0; i < frame.data.length; i++) {
                e.putInt("status_b" + i, frame.unsigned(i));
            }
        }

        e.putString("doors_text", doorsText(mask));
    }

    static String doorsText(int mask) {
        StringBuilder s = new StringBuilder();
        append(s, mask, FRONT_LEFT, "водительская");
        append(s, mask, FRONT_RIGHT, "передняя пассажирская");
        append(s, mask, REAR_LEFT, "задняя левая");
        append(s, mask, REAR_RIGHT, "задняя правая");
        append(s, mask, BOOT, "багажник");
        return s.length() == 0 ? "Все двери закрыты" : "Открыто: " + s;
    }

    private static void append(StringBuilder s, int mask, int bit, String name) {
        if ((mask & bit) == 0) return;
        if (s.length() > 0) s.append(", ");
        s.append(name);
    }

    static String telemetrySummary(SharedPreferences p) {
        long ts = p.getLong("telemetry_last_ms", 0);
        if (ts == 0) {
            return "Телеметрия пока не распознана.\nНужен живой статус RP5 при включённом зажигании.";
        }

        int b0 = p.getInt("status_b0", -1);
        int b3 = p.getInt("status_b3", -1);
        int b4 = p.getInt("status_b4", -1);
        int b8 = p.getInt("status_b8", -1);

        return "Сырые кандидаты RP5:\n" +
                "B0 = " + value(b0) + "   B3 = " + value(b3) + "\n" +
                "B4 = " + value(b4) + "   B8 = " + value(b8) + "\n" +
                "B8 в вашем тесте был 89 — похож на температурный параметр, но пока НЕ подписываем его как °C.\n" +
                "Расход / наружная t° / топливо / запас хода: ждут расшифровки.";
    }

    static String rawStatusBytes(SharedPreferences p) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            int v = p.getInt("status_b" + i, -1);
            if (i > 0) s.append(' ');
            s.append(v < 0 ? "--" : String.format(Locale.US, "%02X", v));
        }
        return s.toString();
    }

    private static String value(int v) {
        return v < 0 ? "—" : String.format(Locale.US, "%d (0x%02X)", v, v);
    }

    private VehicleData() {}
}
