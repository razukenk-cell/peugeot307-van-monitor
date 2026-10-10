package com.razukenk.peugeot307vanmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class DirectCarDataReader {
    private static final int MISSING_INT = Integer.MIN_VALUE + 307;

    private static final String KEY_DOOR = "state.canbus.door_state.i";
    private static final String KEY_TURN = "state.canbus.turn_state.i";
    private static final String KEY_OUT_TEMP = "state.canbus.out_temp.s";
    private static final String KEY_CANBOX_VERSION = "state.canbus.canbox_version.s";
    private static final String KEY_CURRENT_CANBUS = "factory.canbus.current_canbus.s";

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
    private Method getIntIndexed;
    private Method getIntArray;
    private Method getLong;
    private Method getLongArray;
    private Method getString;
    private Method getStringIndexed;
    private Method getStringArray;

    private final Set<String> knownKeys = new LinkedHashSet<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    private boolean started;
    private int lastDoor = Integer.MIN_VALUE;
    private int lastTurn = Integer.MIN_VALUE;
    private String lastOutTemp;
    private String lastCanboxVersion;
    private String lastCurrentCanbus;
    private long lastRediscoverMs;

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

            hasKey = find(carDataClass, "hasKey", String.class);
            listKey = find(carDataClass, "listKey", String[].class);

            getBool = find(carDataClass, "getBool", String.class, boolean.class);
            getBoolArray = find(carDataClass, "getBoolArray", String.class);
            getByte = find(carDataClass, "getByte", String.class, int.class);
            getByteArray = find(carDataClass, "getByteArray", String.class);
            getDouble = find(carDataClass, "getDouble", String.class, double.class);
            getDoubleArray = find(carDataClass, "getDoubleArray", String.class);
            getFloat = find(carDataClass, "getFloat", String.class, float.class);
            getFloatArray = find(carDataClass, "getFloatArray", String.class);

            // There are TWO integer APIs on this firmware:
            // getInt(key, default) for common global state and
            // getInt(key, index, default) for CANBUS values.
            getInt = find(carDataClass, "getInt", String.class, int.class);
            getIntIndexed = find(carDataClass, "getInt", String.class, int.class, int.class);
            getIntArray = find(carDataClass, "getIntArray", String.class);

            getLong = find(carDataClass, "getLong", String.class, long.class);
            getLongArray = find(carDataClass, "getLongArray", String.class);

            // Same story for strings: CANBUS properties use getString(key, index).
            getString = find(carDataClass, "getString", String.class);
            getStringIndexed = find(carDataClass, "getString", String.class, int.class);
            getStringArray = find(carDataClass, "getStringArray", String.class);

            removeListener = find(carDataClass, "removeListener",
                    Class.forName("android.cartech.cardata.CarData$CarDataListener"));

            reflectCarDataApi(carDataClass);
            discoverKeys();
            registerListener(carDataClass);

            prefs.edit()
                    .putString("direct_status", "ПОДКЛЮЧЕНО к android.cartech.cardata.CarData")
                    .putInt("direct_key_count", knownKeys.size())
                    .putLong("direct_connected_ms", System.currentTimeMillis())
                    .putBoolean("direct_exact_api",
                            getIntIndexed != null && getStringIndexed != null)
                    .apply();

            append("# DIRECT CarData connected; keys=" + knownKeys.size()
                    + " indexedInt=" + (getIntIndexed != null)
                    + " indexedString=" + (getStringIndexed != null));

            pollExactCanbus();
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

    private static Method find(Class<?> cls, String name, Class<?>... args) {
        try {
            return cls.getMethod(name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void discoverKeys() {
        if (carData == null || listKey == null) return;

        String[][] filters = new String[][]{
                new String[]{"*"},
                new String[]{"state.canbus.", "data.canbus.", "factory.canbus."},
                new String[]{KEY_DOOR, KEY_TURN, KEY_OUT_TEMP, KEY_CANBOX_VERSION, KEY_CURRENT_CANBUS}
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

        knownKeys.addAll(Arrays.asList(
                KEY_DOOR, KEY_TURN, KEY_OUT_TEMP, KEY_CANBOX_VERSION, KEY_CURRENT_CANBUS,
                "state.main.acc_on.z", "state.main.headlight_on.z",
                "state.main.brake_state.z", "state.main.backcar_state.z",
                "state.main.battery_volt.i"
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
                        if ("onDataChanged".equals(method.getName())) {
                            append("CALLBACK " + method.toGenericString() + " args=" + describeArgs(args));
                            if (args != null && args.length >= 1) {
                                String key = String.valueOf(args[0]);
                                onChanged(key,
                                        args.length > 1 ? args[1] : null,
                                        args.length > 2 ? args[2] : null);
                            }
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
                    "listener недоступен; точный опрос работает: " +
                            t.getClass().getSimpleName()).apply();
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
        addRecent(row);

        prefs.edit()
                .putString("direct_last_key", key)
                .putString("direct_last_value", value)
                .putInt("direct_key_count", knownKeys.size())
                .putLong("direct_last_ms", System.currentTimeMillis())
                .apply();

        if (key.contains(".canbus.")) {
            appendDeep(timestamp() + " EVENT MATRIX " + probeKeyMatrix(key));
        }
    }

    synchronized void poll() {
        if (carData == null) return;

        pollExactCanbus();

        long now = System.currentTimeMillis();
        if (now - lastRediscoverMs > 5000) {
            lastRediscoverMs = now;
            discoverKeysQuietly();
        }

        snapshotInteresting();
    }

    private void discoverKeysQuietly() {
        if (listKey == null) return;
        try {
            Object value = listKey.invoke(carData, (Object) new String[]{"*"});
            if (value instanceof String[]) {
                String[] arr = (String[]) value;
                int before = knownKeys.size();
                knownKeys.addAll(Arrays.asList(arr));
                if (knownKeys.size() != before) {
                    prefs.edit().putInt("direct_key_count", knownKeys.size()).apply();
                    append("# REDISCOVER keys=" + knownKeys.size());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void pollExactCanbus() {
        int door = getCanInt(KEY_DOOR, MISSING_INT);
        int turn = getCanInt(KEY_TURN, MISSING_INT);
        String outTemp = getCanString(KEY_OUT_TEMP);
        String canboxVersion = getCanString(KEY_CANBOX_VERSION);
        String currentCanbus = getCanString(KEY_CURRENT_CANBUS);

        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;

        if (door != MISSING_INT) {
            e.putInt("direct_door_state", door);
            e.putString("direct_door_state_hex", String.format(Locale.US, "0x%02X", door & 0xFF));

            int rp5Mask = genericDoorToRp5Mask(door);
            e.putInt("door_mask", rp5Mask);
            e.putString("doors_text", VehicleData.doorsText(rp5Mask));
            e.putBoolean("direct_hood_open", (door & 0x20) != 0);

            if (door != lastDoor) {
                lastDoor = door;
                changed = true;
                String row = "DIRECT " + KEY_DOOR + " = " +
                        String.format(Locale.US, "0x%02X", door & 0xFF) +
                        " => " + VehicleData.doorsText(rp5Mask) +
                        (((door & 0x20) != 0) ? ", капот открыт" : "");
                append(row);
                addRecent(row);
            }
        }

        if (turn != MISSING_INT) {
            e.putInt("direct_turn_state", turn);
            if (turn != lastTurn) {
                lastTurn = turn;
                changed = true;
                String row = "DIRECT " + KEY_TURN + " = " + turn;
                append(row);
                addRecent(row);
            }
        }

        if (outTemp != null) {
            e.putString("direct_out_temp", outTemp);
            if (!outTemp.equals(lastOutTemp)) {
                lastOutTemp = outTemp;
                changed = true;
                String row = "DIRECT " + KEY_OUT_TEMP + " = " + outTemp;
                append(row);
                addRecent(row);
            }
        }

        if (canboxVersion != null) {
            e.putString("direct_canbox_version", canboxVersion);
            if (!canboxVersion.equals(lastCanboxVersion)) {
                lastCanboxVersion = canboxVersion;
                append("DIRECT " + KEY_CANBOX_VERSION + " = " + canboxVersion);
            }
        }

        if (currentCanbus != null) {
            e.putString("direct_current_canbus", currentCanbus);
            if (!currentCanbus.equals(lastCurrentCanbus)) {
                lastCurrentCanbus = currentCanbus;
                append("DIRECT " + KEY_CURRENT_CANBUS + " = " + currentCanbus);
            }
        }

        if (changed) {
            e.putLong("direct_canbus_last_ms", System.currentTimeMillis())
                    .putLong("last_activity_ms", System.currentTimeMillis());
        }

        e.putString("direct_recent", recentText());
        e.apply();
    }

    private int getCanInt(String key, int def) {
        if (carData == null) return def;

        if (getIntIndexed != null) {
            try {
                Object v = getIntIndexed.invoke(carData, key, 0, def);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable t) {
                prefs.edit().putString("direct_exact_error",
                        key + ": " + t.getClass().getSimpleName()).apply();
            }
        }

        if (getInt != null) {
            try {
                Object v = getInt.invoke(carData, key, def);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }

        return def;
    }

    private String getCanString(String key) {
        if (carData == null) return null;

        if (getStringIndexed != null) {
            try {
                Object v = getStringIndexed.invoke(carData, key, 0);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable t) {
                prefs.edit().putString("direct_exact_error",
                        key + ": " + t.getClass().getSimpleName()).apply();
            }
        }

        if (getString != null) {
            try {
                Object v = getString.invoke(carData, key);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private int genericDoorToRp5Mask(int generic) {
        int out = 0;
        if ((generic & 0x01) != 0) out |= VehicleData.FRONT_LEFT;
        if ((generic & 0x02) != 0) out |= VehicleData.FRONT_RIGHT;
        if ((generic & 0x04) != 0) out |= VehicleData.REAR_LEFT;
        if ((generic & 0x08) != 0) out |= VehicleData.REAR_RIGHT;
        if ((generic & 0x10) != 0) out |= VehicleData.BOOT;
        return out;
    }

    private void snapshotInteresting() {
        StringBuilder sb = new StringBuilder();

        appendSnapshot(sb, KEY_DOOR, getCanInt(KEY_DOOR, MISSING_INT));
        appendSnapshot(sb, KEY_TURN, getCanInt(KEY_TURN, MISSING_INT));
        appendSnapshot(sb, KEY_OUT_TEMP, getCanString(KEY_OUT_TEMP));
        appendSnapshot(sb, KEY_CANBOX_VERSION, getCanString(KEY_CANBOX_VERSION));
        appendSnapshot(sb, KEY_CURRENT_CANBUS, getCanString(KEY_CURRENT_CANBUS));

        String[] common = {
                "state.main.acc_on.z",
                "state.main.headlight_on.z",
                "state.main.brake_state.z",
                "state.main.backcar_state.z",
                "state.main.battery_volt.i"
        };

        for (String key : common) {
            String value = readKey(key);
            if (!"<missing>".equals(value)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(key).append(" = ").append(value);
            }
        }

        prefs.edit()
                .putString("direct_snapshot", sb.length() == 0 ? "(none)" : sb.toString())
                .putLong("direct_last_ms", System.currentTimeMillis())
                .apply();
    }

    private void appendSnapshot(StringBuilder sb, String key, int value) {
        if (value == MISSING_INT) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(key).append(" = ").append(value);
    }

    private void appendSnapshot(StringBuilder sb, String key, String value) {
        if (value == null) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(key).append(" = ").append(value);
    }

    private String readKey(String key) {
        if (carData == null) return "<offline>";

        try {
            if (key.endsWith(".z") && getBool != null)
                return String.valueOf(getBool.invoke(carData, key, false));
            if (key.endsWith(".Z") && getBoolArray != null)
                return arrayToString(getBoolArray.invoke(carData, key));
            if (key.endsWith(".b") && getByte != null)
                return String.valueOf(getByte.invoke(carData, key, Integer.MIN_VALUE));
            if (key.endsWith(".B") && getByteArray != null)
                return hexArray(getByteArray.invoke(carData, key));
            if (key.endsWith(".d") && getDouble != null)
                return String.valueOf(getDouble.invoke(carData, key, Double.NaN));
            if (key.endsWith(".D") && getDoubleArray != null)
                return arrayToString(getDoubleArray.invoke(carData, key));
            if (key.endsWith(".f") && getFloat != null)
                return String.valueOf(getFloat.invoke(carData, key, Float.NaN));
            if (key.endsWith(".F") && getFloatArray != null)
                return arrayToString(getFloatArray.invoke(carData, key));
            if (key.endsWith(".i")) {
                int v = getCanInt(key, MISSING_INT);
                return v == MISSING_INT ? "<missing>" : String.valueOf(v);
            }
            if (key.endsWith(".I") && getIntArray != null)
                return arrayToString(getIntArray.invoke(carData, key));
            if (key.endsWith(".l") && getLong != null)
                return String.valueOf(getLong.invoke(carData, key, Long.MIN_VALUE));
            if (key.endsWith(".L") && getLongArray != null)
                return arrayToString(getLongArray.invoke(carData, key));
            if (key.endsWith(".s")) {
                String v = getCanString(key);
                return v == null ? "<missing>" : v;
            }
            if (key.endsWith(".S") && getStringArray != null)
                return arrayToString(getStringArray.invoke(carData, key));
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }

        return "<missing>";
    }

    private void addRecent(String row) {
        recent.addFirst(row);
        while (recent.size() > 20) recent.removeLast();
    }

    private String recentText() {
        StringBuilder sb = new StringBuilder();
        for (String x : recent) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        return sb.toString();
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


    synchronized String runDeepProbe() {
        if (carData == null) return "CarData не подключён";
        StringBuilder out = new StringBuilder();
        out.append("# Peugeot 307 CarInfo v0.11 CarData deep read-only probe\n");
        out.append("# Only getter/listener/reflection calls; no setters are invoked\n");
        out.append("time=").append(new Date()).append('\n');

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(KEY_DOOR);
        keys.add(KEY_TURN);
        keys.add(KEY_OUT_TEMP);
        keys.add(KEY_CANBOX_VERSION);
        keys.add(KEY_CURRENT_CANBUS);
        for (String k : knownKeys) {
            if (k.contains(".canbus.")) keys.add(k);
            if (keys.size() >= 100) break;
        }

        int tested = 0;
        for (String key : keys) {
            out.append(probeKeyMatrix(key)).append('\n');
            if (++tested >= 100) break;
        }
        out.append("tested=").append(tested).append('\n');
        writeDeep(out.toString(), false);
        String summary = "CarData getter matrix: keys=" + tested
                + "\nlistener=" + prefs.getString("direct_listener", "?")
                + "\nсмотри [CARDATA DEEP PROBE] в диагностике";
        prefs.edit().putString("direct_deep_probe_summary", summary)
                .putLong("direct_deep_probe_ms", System.currentTimeMillis()).apply();
        return summary;
    }

    private String probeKeyMatrix(String key) {
        StringBuilder sb = new StringBuilder();
        sb.append("KEY ").append(key);
        if (hasKey != null) {
            try { sb.append(" hasKey=").append(hasKey.invoke(carData, key)); }
            catch (Throwable t) { sb.append(" hasKeyErr=").append(rootError(t)); }
        }

        if (key.endsWith(".i")) {
            final int sentinel = 0x6A11D00D;
            if (getInt != null) {
                try { sb.append(" getInt=").append(getInt.invoke(carData, key, sentinel)); }
                catch (Throwable t) { sb.append(" getIntErr=").append(rootError(t)); }
            }
            if (getIntIndexed != null) {
                sb.append(" indexed{");
                String firstErr = null;
                boolean any = false;
                for (int i = 0; i < 16; i++) {
                    try {
                        Object v = getIntIndexed.invoke(carData, key, i, sentinel);
                        if (v instanceof Integer && ((Integer) v) != sentinel) {
                            if (any) sb.append(',');
                            sb.append(i).append('=').append(v);
                            any = true;
                        }
                    } catch (Throwable t) {
                        if (firstErr == null) firstErr = rootError(t);
                    }
                }
                if (!any) sb.append("none");
                if (firstErr != null) sb.append(" err=").append(firstErr);
                sb.append('}');
            }
            if (getIntArray != null) {
                try { sb.append(" intArray=").append(arrayToString(getIntArray.invoke(carData, key))); }
                catch (Throwable t) { sb.append(" intArrayErr=").append(rootError(t)); }
            }
        } else if (key.endsWith(".s")) {
            if (getString != null) {
                try { sb.append(" getString=").append(String.valueOf(getString.invoke(carData, key))); }
                catch (Throwable t) { sb.append(" getStringErr=").append(rootError(t)); }
            }
            if (getStringIndexed != null) {
                sb.append(" indexed{");
                String firstErr = null;
                boolean any = false;
                for (int i = 0; i < 16; i++) {
                    try {
                        Object v = getStringIndexed.invoke(carData, key, i);
                        String x = v == null ? "" : String.valueOf(v);
                        if (!x.isEmpty()) {
                            if (any) sb.append(',');
                            sb.append(i).append('=').append(x);
                            any = true;
                        }
                    } catch (Throwable t) {
                        if (firstErr == null) firstErr = rootError(t);
                    }
                }
                if (!any) sb.append("none");
                if (firstErr != null) sb.append(" err=").append(firstErr);
                sb.append('}');
            }
            if (getStringArray != null) {
                try { sb.append(" stringArray=").append(arrayToString(getStringArray.invoke(carData, key))); }
                catch (Throwable t) { sb.append(" stringArrayErr=").append(rootError(t)); }
            }
        } else {
            sb.append(" typedRead=").append(readKey(key));
        }
        return sb.toString();
    }

    private void reflectCarDataApi(Class<?> carDataClass) {
        StringBuilder out = new StringBuilder();
        out.append("# CarData reflection v0.11\n");
        Class<?>[] classes;
        try {
            classes = new Class<?>[]{
                    carDataClass,
                    Class.forName("android.cartech.cardata.KeyFilter"),
                    Class.forName("android.cartech.cardata.CarData$CarDataListener")
            };
        } catch (Throwable t) {
            classes = new Class<?>[]{carDataClass};
        }
        for (Class<?> c : classes) {
            out.append("CLASS ").append(c.getName())
                    .append(" loader=").append(String.valueOf(c.getClassLoader())).append('\n');
            try {
                for (Method m : c.getDeclaredMethods()) {
                    out.append("  METHOD ").append(Modifier.toString(m.getModifiers()))
                            .append(' ').append(m.toGenericString()).append('\n');
                }
            } catch (Throwable t) { out.append("  methods error ").append(rootError(t)).append('\n'); }
            try {
                for (Field f : c.getDeclaredFields()) {
                    out.append("  FIELD ").append(Modifier.toString(f.getModifiers())).append(' ')
                            .append(f.getType().getName()).append(' ').append(f.getName()).append('\n');
                }
            } catch (Throwable t) { out.append("  fields error ").append(rootError(t)).append('\n'); }
        }
        writeReflection(out.toString());
        prefs.edit().putString("direct_reflection_status", "CarData API reflection записан").apply();
    }

    private static String describeArgs(Object[] args) {
        if (args == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            sb.append(i).append(':').append(a == null ? "null" : a.getClass().getName())
                    .append('=').append(String.valueOf(a));
        }
        return sb.append(']').toString();
    }

    private static String rootError(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String m = x.getMessage();
        return x.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ":" + m.replace('\n', ' '));
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void appendDeep(String line) { writeDeep(line + "\n", true); }

    private void writeReflection(String text) {
        try {
            File file = new File(context.getFilesDir(), "cardata_api_reflection.log");
            try (FileWriter w = new FileWriter(file, false)) { w.write(text); }
        } catch (Throwable ignored) {}
    }

    private void writeDeep(String text, boolean appendFile) {
        try {
            File file = new File(context.getFilesDir(), "cardata_deep_probe.log");
            try (FileWriter w = new FileWriter(file, appendFile)) { w.write(text); }
        } catch (Throwable ignored) {}
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
