package com.razukenk.peugeot307vanmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class DirectCarDataReader {
    private final Context context;
    private final SharedPreferences prefs;

    private Object carData;
    private Object listener;
    private Method removeListener;

    private Method hasKey;
    private Method listKey;
    private Method getBool;
    private Method getBoolArray;
    private Method getByte;
    private Method getByteArray;
    private Method getDouble;
    private Method getDoubleArray;
    private Method getFloat;
    private Method getFloatArray;
    private Method getInt;
    private Method getIntArray;
    private Method getLong;
    private Method getLongArray;
    private Method getString;
    private Method getStringArray;

    private final Set<String> knownKeys = new LinkedHashSet<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private boolean started;

    DirectCarDataReader(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
    }

    synchronized boolean start() {
        if (started) return carData != null;
        started = true;

        try {
            Class<?> carDataClass = Class.forName("android.cartech.cardata.CarData");
            Method get = carDataClass.getMethod("get");
            carData = get.invoke(null);
            if (carData == null) throw new IllegalStateException("CarData.get() returned null");

            try {
                Method setPackageName = carDataClass.getMethod("setPackageName", String.class);
                setPackageName.invoke(carData, context.getPackageName());
            } catch (Throwable ignored) {
            }

            hasKey = carDataClass.getMethod("hasKey", String.class);
            listKey = carDataClass.getMethod("listKey", String[].class);
            getBool = carDataClass.getMethod("getBool", String.class, boolean.class);
            getBoolArray = carDataClass.getMethod("getBoolArray", String.class);
            getByte = carDataClass.getMethod("getByte", String.class, int.class);
            getByteArray = carDataClass.getMethod("getByteArray", String.class);
            getDouble = carDataClass.getMethod("getDouble", String.class, double.class);
            getDoubleArray = carDataClass.getMethod("getDoubleArray", String.class);
            getFloat = carDataClass.getMethod("getFloat", String.class, float.class);
            getFloatArray = carDataClass.getMethod("getFloatArray", String.class);
            getInt = carDataClass.getMethod("getInt", String.class, int.class);
            getIntArray = carDataClass.getMethod("getIntArray", String.class);
            getLong = carDataClass.getMethod("getLong", String.class, long.class);
            getLongArray = carDataClass.getMethod("getLongArray", String.class);
            getString = carDataClass.getMethod("getString", String.class);
            getStringArray = carDataClass.getMethod("getStringArray", String.class);
            removeListener = carDataClass.getMethod("removeListener",
                    Class.forName("android.cartech.cardata.CarData$CarDataListener"));

            discoverKeys();
            registerListener(carDataClass);

            prefs.edit()
                    .putString("direct_status", "ПОДКЛЮЧЕНО к android.cartech.cardata.CarData")
                    .putInt("direct_key_count", knownKeys.size())
                    .putLong("direct_connected_ms", System.currentTimeMillis())
                    .apply();

            append("# DIRECT CarData connected; keys=" + knownKeys.size());
            snapshotInteresting();
            return true;
        } catch (Throwable t) {
            prefs.edit()
                    .putString("direct_status", "не подключено: " +
                            t.getClass().getSimpleName() + ": " + safe(t.getMessage()))
                    .apply();
            append("# DIRECT connect failed: " + t);
            carData = null;
            return false;
        }
    }

    private void discoverKeys() {
        if (carData == null || listKey == null) return;

        String[][] filters = new String[][]{
                new String[]{"*"},
                new String[]{"CAR_INFO", "CAR_INFO_1", "CAR_INFO_2", "CAR_INFO_3", "CAR_INFO_4",
                        "BASE_INFO", "BASE_INFO_1", "CAR_SPEED", "OUT_TEMP", "ENV_TEMP",
                        "OUTSIDETEMP", "AIR", "AIR_INFO", "WHEEL_KEY", "PANEL_KEY", "KNOB_KEY",
                        "CAR_TIME", "CAR_MODEL", "SYSTEM_INFO", "VERSION", "CAR_CANBUS_INFO",
                        "CAR_CANBUS_AIR"}
        };

        for (String[] filter : filters) {
            try {
                Object value = listKey.invoke(carData, (Object) filter);
                if (value instanceof String[]) {
                    knownKeys.addAll(Arrays.asList((String[]) value));
                }
            } catch (Throwable ignored) {
            }
        }

        // Exact names are useful on firmwares where listKey("*") is restricted.
        knownKeys.addAll(Arrays.asList(
                "CAR_SPEED", "OUT_TEMP", "ENV_TEMP", "OUTSIDETEMP",
                "CAR_INFO", "CAR_INFO_1", "CAR_INFO_2", "CAR_INFO_3", "CAR_INFO_4",
                "BASE_INFO", "BASE_INFO_1", "AIR", "AIR_INFO", "WHEEL_KEY",
                "PANEL_KEY", "KNOB_KEY", "CAR_TIME", "CAR_MODEL", "SYSTEM_INFO", "VERSION"
        ));

        append("# KEYS");
        int count = 0;
        for (String key : knownKeys) {
            append("KEY " + key);
            if (++count >= 2500) break;
        }
    }

    private void registerListener(Class<?> carDataClass) {
        try {
            Class<?> keyFilterClass = Class.forName("android.cartech.cardata.KeyFilter");
            Class<?> listenerClass = Class.forName("android.cartech.cardata.CarData$CarDataListener");

            Method make = keyFilterClass.getMethod("make", String[].class);
            Object filter = make.invoke(null, (Object) new String[]{"*"});

            listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (proxy, method, args) -> {
                        if ("onDataChanged".equals(method.getName()) && args != null && args.length >= 1) {
                            String key = String.valueOf(args[0]);
                            onChanged(key,
                                    args.length > 1 ? args[1] : null,
                                    args.length > 2 ? args[2] : null);
                        }
                        return null;
                    });

            Method addListener = carDataClass.getMethod(
                    "addListener", String.class, keyFilterClass, listenerClass);

            String client = "Peugeot307CarInfo-" + Process.myPid();
            Object result = addListener.invoke(carData, client, filter, listener);
            append("# addListener result=" + result + " client=" + client);
            prefs.edit().putString("direct_listener", "активен, result=" + result).apply();
        } catch (Throwable t) {
            append("# listener failed: " + t);
            prefs.edit().putString("direct_listener",
                    "listener недоступен; работает опрос: " + t.getClass().getSimpleName()).apply();
        }
    }

    private synchronized void onChanged(String key, Object type, Object reason) {
        if (key == null || key.isEmpty()) return;
        knownKeys.add(key);

        String value = readKey(key);
        String row = key + " = " + value +
                "   type=" + String.valueOf(type) +
                "   reason=" + String.valueOf(reason);

        append("CHANGE " + row);
        recent.addFirst(row);
        while (recent.size() > 20) recent.removeLast();

        StringBuilder sb = new StringBuilder();
        for (String x : recent) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }

        prefs.edit()
                .putString("direct_last_key", key)
                .putString("direct_last_value", value)
                .putString("direct_recent", sb.toString())
                .putInt("direct_key_count", knownKeys.size())
                .putLong("direct_last_ms", System.currentTimeMillis())
                .apply();
    }

    synchronized void poll() {
        if (carData == null) return;
        snapshotInteresting();
    }

    private void snapshotInteresting() {
        String[] interesting = {
                "CAR_SPEED", "OUT_TEMP", "ENV_TEMP", "OUTSIDETEMP",
                "CAR_INFO", "CAR_INFO_1", "CAR_INFO_2", "CAR_INFO_3", "CAR_INFO_4",
                "BASE_INFO", "BASE_INFO_1", "AIR", "AIR_INFO", "WHEEL_KEY",
                "PANEL_KEY", "KNOB_KEY", "CAR_TIME"
        };

        StringBuilder sb = new StringBuilder();
        for (String key : interesting) {
            String value = readKey(key);
            if ("<missing>".equals(value)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append(" = ").append(value);
        }

        if (sb.length() > 0) {
            prefs.edit()
                    .putString("direct_snapshot", sb.toString())
                    .putLong("direct_last_ms", System.currentTimeMillis())
                    .apply();
        }
    }

    private String readKey(String key) {
        if (carData == null) return "<offline>";

        try {
            Object exists = hasKey.invoke(carData, key);
            if (exists instanceof Boolean && !((Boolean) exists)) {
                // Typed keys may be returned by listKey; exact aliases can still be queried below.
                if (key.contains(".")) return "<missing>";
            }
        } catch (Throwable ignored) {
        }

        try {
            if (key.endsWith(".z")) return String.valueOf(getBool.invoke(carData, key, false));
            if (key.endsWith(".Z")) return arrayToString(getBoolArray.invoke(carData, key));
            if (key.endsWith(".b")) return String.valueOf(getByte.invoke(carData, key, Integer.MIN_VALUE));
            if (key.endsWith(".B")) return hexArray(getByteArray.invoke(carData, key));
            if (key.endsWith(".d")) return String.valueOf(getDouble.invoke(carData, key, Double.NaN));
            if (key.endsWith(".D")) return arrayToString(getDoubleArray.invoke(carData, key));
            if (key.endsWith(".f")) return String.valueOf(getFloat.invoke(carData, key, Float.NaN));
            if (key.endsWith(".F")) return arrayToString(getFloatArray.invoke(carData, key));
            if (key.endsWith(".i")) return String.valueOf(getInt.invoke(carData, key, Integer.MIN_VALUE));
            if (key.endsWith(".I")) return arrayToString(getIntArray.invoke(carData, key));
            if (key.endsWith(".l")) return String.valueOf(getLong.invoke(carData, key, Long.MIN_VALUE));
            if (key.endsWith(".L")) return arrayToString(getLongArray.invoke(carData, key));
            if (key.endsWith(".s")) return String.valueOf(getString.invoke(carData, key));
            if (key.endsWith(".S")) return arrayToString(getStringArray.invoke(carData, key));
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }

        // Untyped aliases used by the stock CANBUS APK are usually arrays.
        try {
            Object v = getIntArray.invoke(carData, key);
            if (v != null) return "I" + arrayToString(v);
        } catch (Throwable ignored) {
        }
        try {
            Object v = getByteArray.invoke(carData, key);
            if (v != null) return "B" + hexArray(v);
        } catch (Throwable ignored) {
        }
        try {
            int v = (Integer) getInt.invoke(carData, key, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) return String.valueOf(v);
        } catch (Throwable ignored) {
        }
        try {
            String v = (String) getString.invoke(carData, key);
            if (v != null) return v;
        } catch (Throwable ignored) {
        }

        return "<missing>";
    }

    private static String arrayToString(Object array) {
        if (array == null) return "null";
        int n = Array.getLength(array);
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(n, 96);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.valueOf(Array.get(array, i)));
        }
        if (n > limit) sb.append(",…").append(n);
        return sb.append(']').toString();
    }

    private static String hexArray(Object array) {
        if (!(array instanceof byte[])) return arrayToString(array);
        byte[] data = (byte[]) array;
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(data.length, 128);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        if (data.length > limit) sb.append(" …").append(data.length);
        return sb.append(']').toString();
    }

    private synchronized void append(String line) {
        try {
            File file = new File(context.getFilesDir(), "direct_car_data.log");
            try (FileWriter w = new FileWriter(file, true)) {
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date());
                w.write(ts + " " + line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    synchronized void stop() {
        if (carData != null && listener != null && removeListener != null) {
            try {
                removeListener.invoke(carData, listener);
            } catch (Throwable ignored) {
            }
        }
        listener = null;
        carData = null;
        started = false;
    }

    boolean isConnected() {
        return carData != null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
