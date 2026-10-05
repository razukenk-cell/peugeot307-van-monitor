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
        e.putString("doors_text", doorsText(mask));

        // Always retain the current status bytes for correlation.
        for (int i = 0; i < frame.data.length; i++) {
            e.putInt("current_status_b" + i, frame.unsigned(i));
        }

        // Strong candidates discovered from the user's synchronized photo/log test.
        int rangeCandidate = (frame.unsigned(1) << 8) | frame.unsigned(2);
        int outsideCandidate = frame.unsigned(9);
        e.putInt("range_candidate", rangeCandidate);
        e.putInt("outside_candidate", outsideCandidate);

        // Preserve richer samples separately when the box provides them.
        if (frame.unsigned(8) != 0xFF) {
            e.putLong("telemetry_last_ms", System.currentTimeMillis());
            for (int i = 0; i < frame.data.length; i++) {
                e.putInt("status_b" + i, frame.unsigned(i));
            }
        }
    }

    static void applyClockFrame(SharedPreferences.Editor e, FrameParser.Frame frame) {
        if (frame.command != 0xC8 || frame.data.length < 4) return;
        int hour = frame.unsigned(2);
        int minute = frame.unsigned(3);
        if (hour <= 23 && minute <= 59) {
            e.putInt("box_hour", hour);
            e.putInt("box_minute", minute);
            e.putLong("box_clock_ms", System.currentTimeMillis());
        }
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

    static String keyName(int id) {
        switch (id & 0xFF) {
            case 0x01: return "Громкость +";
            case 0x02: return "Громкость −";
            case 0x05: return "SOURCE";
            case 0x06: return "Колесико вверх";
            case 0x07: return "Колесико вниз";
            case 0x80: return "Кнопка БК";
            default: return String.format(Locale.US, "Неизвестная 0x%02X", id & 0xFF);
        }
    }

    static String telemetrySummary(SharedPreferences p) {
        int range = p.getInt("range_candidate", -1);
        int outside = p.getInt("outside_candidate", -1);
        int hour = p.getInt("box_hour", -1);
        int minute = p.getInt("box_minute", -1);

        StringBuilder s = new StringBuilder();

        String directTemp = p.getString("direct_out_temp", "");
        s.append("Наружная t° DIRECT: ");
        s.append(directTemp == null || directTemp.isEmpty() ? "—" : directTemp);

        s.append("\nЗапас хода RAW: ");
        s.append(range < 0 ? "—" : range + " км (совпало с дисплеем)");

        s.append("\nНаружная t° RAW: ");
        s.append(outside < 0 || outside == 0xFF ? "—" : outside + " °C (совпало с дисплеем)");

        s.append("\nВремя CAN-box: ");
        if (hour >= 0 && minute >= 0) {
            s.append(String.format(Locale.US, "%02d:%02d", hour, minute));
        } else {
            s.append("—");
        }

        long rich = p.getLong("telemetry_last_ms", 0);
        if (rich > 0) {
            int b0 = p.getInt("status_b0", -1);
            int b3 = p.getInt("status_b3", -1);
            int b4 = p.getInt("status_b4", -1);
            int b8 = p.getInt("status_b8", -1);
            s.append("\nRich: B0=").append(value(b0))
                    .append(" B3=").append(value(b3))
                    .append(" B4=").append(value(b4))
                    .append(" B8=").append(value(b8));
        }
        s.append("\nРасход/средняя скорость пока ещё не выведены напрямую.");
        return s.toString();
    }

    static String rawStatusBytes(SharedPreferences p) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            int v = p.getInt("current_status_b" + i, -1);
            if (i > 0) s.append(' ');
            s.append(v < 0 ? "--" : String.format(Locale.US, "%02X", v));
        }
        return s.toString();
    }

    private static String value(int v) {
        return v < 0 ? "—" : String.format(Locale.US, "%d(0x%02X)", v, v);
    }

    private VehicleData() {}
}
