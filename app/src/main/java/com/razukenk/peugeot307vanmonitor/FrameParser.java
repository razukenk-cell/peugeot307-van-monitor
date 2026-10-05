package com.razukenk.peugeot307vanmonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FrameParser {
    static final class Frame {
        final String direction;
        final int command;
        final byte[] data;
        final boolean checksumOk;
        final String hex;

        Frame(String direction, int command, byte[] data, boolean checksumOk, String hex) {
            this.direction = direction;
            this.command = command;
            this.data = data;
            this.checksumOk = checksumOk;
            this.hex = hex;
        }

        int unsigned(int index) {
            return data[index] & 0xFF;
        }

        String payloadHex() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < data.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
            }
            return sb.toString();
        }
    }

    static Frame parseLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        String direction = "SCREEN";
        int start = -1;

        int arrow = trimmed.indexOf("--->");
        if (arrow >= 0) {
            direction = "--->";
            start = arrow + 4;
        } else {
            arrow = trimmed.indexOf("<---");
            if (arrow >= 0) {
                direction = "<---";
                start = arrow + 4;
            }
        }

        if (start < 0) {
            String upper = trimmed.toUpperCase(Locale.US);
            start = upper.indexOf("2E ");
            if (start < 0 && upper.equals("2E")) start = 0;
            if (start < 0) return null;
        }

        String hexPart = trimmed.substring(start).trim();
        String[] pieces = hexPart.split("\\s+");
        List<Integer> bytes = new ArrayList<>();
        for (String piece : pieces) {
            String clean = piece.replaceAll("^[^0-9A-Fa-f]+|[^0-9A-Fa-f]+$", "");
            if (!clean.matches("(?i)[0-9a-f]{2}")) {
                if (bytes.isEmpty()) continue;
                break;
            }
            bytes.add(Integer.parseInt(clean, 16));
        }

        if (bytes.size() < 4 || bytes.get(0) != 0x2E) return null;

        int len = bytes.get(2);
        int expected = len + 4;
        if (bytes.size() < expected) return null;

        int sum = 0;
        for (int i = 1; i < expected; i++) {
            sum = (sum + bytes.get(i)) & 0xFF;
        }
        boolean checksumOk = sum == 0xFF;

        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = (byte) (bytes.get(3 + i) & 0xFF);
        }

        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < expected; i++) {
            if (i > 0) normalized.append(' ');
            normalized.append(String.format(Locale.US, "%02X", bytes.get(i)));
        }

        return new Frame(direction, bytes.get(1), data, checksumOk, normalized.toString());
    }

    private FrameParser() {}
}
