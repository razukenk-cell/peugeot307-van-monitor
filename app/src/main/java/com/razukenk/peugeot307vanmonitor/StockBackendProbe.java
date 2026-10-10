package com.razukenk.peugeot307vanmonitor;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * v0.11: strictly passive discovery of the stock CANBUS backend.
 *
 * Safety:
 * - never opens /dev/ttyCanbus;
 * - never starts stock activities or services;
 * - bindService uses flags=0, never BIND_AUTO_CREATE;
 * - no custom Binder transact() calls;
 * - APK files are read only for metadata/strings;
 * - broadcasts are only listened to, never sent.
 */
final class StockBackendProbe {
    static final String STOCK_PACKAGE = "com.cartech.service.canbus";
    private static final int MAX_STRINGS = 1800;
    private static final int MAX_ACTIONS = 160;
    private static final long MAX_ENTRY_BYTES = 24L * 1024L * 1024L;

    private static final Object RECEIVER_LOCK = new Object();
    private static BroadcastReceiver receiver;
    private static Context receiverContext;
    private static final Set<String> actionCandidates = new LinkedHashSet<>();

    static Result run(Context rawContext, boolean probeExistingBinders) {
        Context context = rawContext.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        StringBuilder out = new StringBuilder(64 * 1024);
        Set<String> interestingStrings = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();

        line(out, "# Peugeot 307 CarInfo v0.11 STOCK BACKEND PROBE");
        line(out, "# PASSIVE ONLY: no UART open/read/write; no stock Activity/Service start; no broadcast send");
        line(out, "time=" + new Date());
        line(out, "our uid=" + Process.myUid() + " package=" + context.getPackageName());

        PackageManager pm = context.getPackageManager();
        PackageInfo pi = null;
        try {
            int flags = PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_META_DATA;
            pi = pm.getPackageInfo(STOCK_PACKAGE, flags);
            ApplicationInfo ai = pi.applicationInfo;
            line(out, "\n[PACKAGE]");
            line(out, "package=" + pi.packageName);
            line(out, "version=" + safe(pi.versionName) + " code=" + packageVersionCode(pi));
            if (ai != null) {
                line(out, "uid=" + ai.uid + " process=" + safe(ai.processName));
                line(out, "sourceDir=" + safe(ai.sourceDir));
                line(out, "publicSourceDir=" + safe(ai.publicSourceDir));
                line(out, "system=" + ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
            }
            try { line(out, "sharedUserId=" + safe(pi.sharedUserId)); } catch (Throwable ignored) {}
            try { line(out, "checkSignatures(our,stock)=" + pm.checkSignatures(context.getPackageName(), STOCK_PACKAGE)); }
            catch (Throwable t) { line(out, "checkSignatures error=" + error(t)); }
            try {
                String[] uidPkgs = ai == null ? null : pm.getPackagesForUid(ai.uid);
                line(out, "packagesForStockUid=" + Arrays.toString(uidPkgs));
            } catch (Throwable t) { line(out, "packagesForStockUid error=" + error(t)); }

            dumpRequestedPermissions(context, pm, pi, out);
            dumpComponents(pi, out);

            if (ai != null && ai.sourceDir != null) {
                scanApk(ai.sourceDir, interestingStrings, actions, out);
                if (ai.splitSourceDirs != null) {
                    for (String split : ai.splitSourceDirs) scanApk(split, interestingStrings, actions, out);
                }
            }
        } catch (Throwable t) {
            line(out, "PACKAGE ERROR: " + error(t));
        }

        line(out, "\n[SYSTEM BINDER REGISTRY - best effort]");
        probeServiceManager(out);

        if (pi != null && probeExistingBinders) {
            line(out, "\n[EXPORTED SERVICE BIND - flags=0 / NO AUTO_CREATE]");
            probeAlreadyRunningExportedServices(context, pi, out);
        }

        line(out, "\n[INTERESTING STOCK APK STRINGS]");
        int n = 0;
        for (String s : interestingStrings) {
            line(out, s);
            if (++n >= MAX_STRINGS) break;
        }

        line(out, "\n[BROADCAST ACTION CANDIDATES]");
        n = 0;
        for (String s : actions) {
            line(out, s);
            if (++n >= MAX_ACTIONS) break;
        }

        synchronized (RECEIVER_LOCK) {
            actionCandidates.clear();
            int count = 0;
            for (String s : actions) {
                actionCandidates.add(s);
                if (++count >= MAX_ACTIONS) break;
            }
        }
        String sniffer = startBroadcastSniffer(context);
        line(out, "broadcastSniffer=" + sniffer);

        String summary = buildSummary(pi, interestingStrings.size(), actions.size(), sniffer, out.toString());
        prefs.edit()
                .putLong("backend_probe_time_ms", System.currentTimeMillis())
                .putString("backend_probe_summary", summary)
                .putInt("backend_probe_string_count", interestingStrings.size())
                .putInt("backend_probe_action_count", actions.size())
                .putString("backend_broadcast_sniffer", sniffer)
                .apply();
        writeFile(context, "stock_backend_probe.log", out.toString(), false);
        return new Result(summary);
    }

    private static void dumpRequestedPermissions(Context context, PackageManager pm, PackageInfo pi, StringBuilder out) {
        line(out, "\n[REQUESTED PERMISSIONS]");
        if (pi.requestedPermissions == null) {
            line(out, "(none)");
            return;
        }
        for (String p : pi.requestedPermissions) {
            int ours = pm.checkPermission(p, context.getPackageName());
            String protection = "?";
            try {
                PermissionInfo info = pm.getPermissionInfo(p, 0);
                protection = protectionName(info.protectionLevel);
            } catch (Throwable ignored) {}
            line(out, p + " ourGrant=" + (ours == PackageManager.PERMISSION_GRANTED) + " protection=" + protection);
        }
    }

    private static void dumpComponents(PackageInfo pi, StringBuilder out) {
        line(out, "\n[ACTIVITIES]");
        if (pi.activities != null) for (ActivityInfo x : pi.activities) line(out, activityComponent(x));
        line(out, "\n[SERVICES]");
        if (pi.services != null) for (ServiceInfo x : pi.services) line(out, serviceComponent(x));
        line(out, "\n[RECEIVERS]");
        if (pi.receivers != null) for (ActivityInfo x : pi.receivers) line(out, activityComponent(x));
        line(out, "\n[PROVIDERS]");
        if (pi.providers != null) for (ProviderInfo x : pi.providers) {
            line(out, baseComponent(x) + " authority=" + safe(x.authority)
                    + " readPerm=" + safe(x.readPermission) + " writePerm=" + safe(x.writePermission)
                    + " grantUri=" + x.grantUriPermissions);
        }
        line(out, "\n[DECLARED PERMISSIONS]");
        if (pi.permissions != null) for (PermissionInfo x : pi.permissions) {
            line(out, x.name + " protection=" + protectionName(x.protectionLevel));
        }
    }

    private static String baseComponent(android.content.pm.ComponentInfo x) {
        return safe(x.name) + " exported=" + x.exported + " enabled=" + x.enabled
                + " process=" + safe(x.processName);
    }

    private static String activityComponent(ActivityInfo x) {
        return baseComponent(x) + " permission=" + safe(x.permission);
    }

    private static String serviceComponent(ServiceInfo x) {
        return baseComponent(x) + " permission=" + safe(x.permission);
    }

    private static void scanApk(String path, Set<String> interesting, Set<String> actions, StringBuilder out) {
        File file = new File(path);
        line(out, "\n[APK SCAN] " + path + " readable=" + file.canRead() + " size=" + file.length());
        if (!file.isFile() || !file.canRead()) return;
        try (ZipFile zip = new ZipFile(file)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            for (ZipEntry e : entries) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                boolean useful = name.matches("classes(\\d*)?\\.dex")
                        || name.equals("AndroidManifest.xml")
                        || name.equals("resources.arsc")
                        || (name.startsWith("lib/") && name.endsWith(".so"));
                if (!useful || e.getSize() > MAX_ENTRY_BYTES) continue;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(e))) {
                    extractAscii(in, interesting, actions);
                } catch (Throwable t) {
                    line(out, "scan entry error " + name + ": " + error(t));
                }
                if (interesting.size() >= MAX_STRINGS && actions.size() >= MAX_ACTIONS) break;
            }
        } catch (Throwable t) {
            line(out, "APK scan error=" + error(t));
        }
    }

    private static void extractAscii(InputStream in, Set<String> interesting, Set<String> actions) throws Exception {
        StringBuilder run = new StringBuilder(256);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xff;
                if (b >= 32 && b <= 126) {
                    if (run.length() < 512) run.append((char) b);
                } else {
                    consumeString(run, interesting, actions);
                    run.setLength(0);
                }
            }
            if (interesting.size() >= MAX_STRINGS && actions.size() >= MAX_ACTIONS) break;
        }
        consumeString(run, interesting, actions);
    }

    private static void consumeString(StringBuilder run, Set<String> interesting, Set<String> actions) {
        if (run.length() < 4) return;
        String s = run.toString().trim();
        if (s.length() < 4 || s.length() > 420) return;
        String lower = s.toLowerCase(Locale.US);
        boolean hit = lower.contains("canbus") || lower.contains("can_box") || lower.contains("canbox")
                || lower.contains("cardata") || lower.contains("car_data") || lower.contains("vehicle")
                || lower.contains("door") || lower.contains("out_temp") || lower.contains("temperature")
                || lower.contains("radar") || lower.contains("air_control") || lower.contains("climate")
                || lower.contains("android.cartech") || lower.contains("com.cartech")
                || lower.startsWith("content://") || lower.contains("ttycanbus")
                || lower.contains("broadcast") || lower.contains("binder") || lower.contains("aidl")
                || lower.contains("service.") || lower.contains(".action.");
        if (hit && interesting.size() < MAX_STRINGS) interesting.add(s);
        if (looksLikeAction(s) && actions.size() < MAX_ACTIONS) actions.add(s);
    }

    private static boolean looksLikeAction(String s) {
        if (s.length() < 8 || s.length() > 180 || s.indexOf(' ') >= 0 || s.indexOf('/') >= 0) return false;
        if (!s.matches("[A-Za-z0-9_.$:-]+")) return false;
        String l = s.toLowerCase(Locale.US);
        if (!(l.contains("action") || l.contains("broadcast") || l.contains("canbus") || l.contains("cardata"))) return false;
        return s.contains(".") && (l.startsWith("com.") || l.startsWith("android.") || l.startsWith("cn."));
    }

    private static void probeServiceManager(StringBuilder out) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method list = sm.getDeclaredMethod("listServices");
            list.setAccessible(true);
            Object result = list.invoke(null);
            if (!(result instanceof String[])) {
                line(out, "listServices returned " + String.valueOf(result));
                return;
            }
            Method getService = sm.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            String[] names = (String[]) result;
            line(out, "count=" + names.length);
            for (String name : names) {
                String l = name.toLowerCase(Locale.US);
                if (!(l.contains("can") || l.contains("car") || l.contains("vehicle") || l.contains("mcu"))) continue;
                String desc = "(descriptor unavailable)";
                try {
                    Object b = getService.invoke(null, name);
                    if (b instanceof IBinder) desc = ((IBinder) b).getInterfaceDescriptor();
                } catch (Throwable t) { desc = "ERR " + error(t); }
                line(out, name + " -> " + desc);
            }
        } catch (Throwable t) {
            line(out, "ServiceManager blocked/unavailable: " + error(t));
        }
    }

    private static void probeAlreadyRunningExportedServices(Context context, PackageInfo pi, StringBuilder out) {
        if (pi.services == null || pi.services.length == 0) {
            line(out, "(no services)");
            return;
        }
        for (ServiceInfo si : pi.services) {
            if (!si.exported || !si.enabled) continue;
            int grant = si.permission == null ? PackageManager.PERMISSION_GRANTED
                    : context.getPackageManager().checkPermission(si.permission, context.getPackageName());
            line(out, "TRY " + si.name + " permission=" + safe(si.permission)
                    + " oursGranted=" + (grant == PackageManager.PERMISSION_GRANTED));
            if (grant != PackageManager.PERMISSION_GRANTED) continue;
            final CountDownLatch latch = new CountDownLatch(1);
            final StringBuilder result = new StringBuilder();
            ServiceConnection conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        result.append("CONNECTED ").append(name.flattenToShortString())
                                .append(" descriptor=").append(service == null ? "null" : service.getInterfaceDescriptor())
                                .append(" alive=").append(service != null && service.isBinderAlive());
                    } catch (Throwable t) { result.append("CONNECTED descriptor error=").append(error(t)); }
                    latch.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    if (result.length() == 0) result.append("DISCONNECTED ").append(name.flattenToShortString());
                    latch.countDown();
                }
                @Override public void onNullBinding(ComponentName name) {
                    result.append("NULL_BINDING ").append(name.flattenToShortString());
                    latch.countDown();
                }
                @Override public void onBindingDied(ComponentName name) {
                    result.append("BINDING_DIED ").append(name.flattenToShortString());
                    latch.countDown();
                }
            };
            boolean bound = false;
            try {
                Intent i = new Intent().setComponent(new ComponentName(STOCK_PACKAGE, si.name));
                bound = context.bindService(i, conn, 0);
                line(out, "  bindService(flags=0) returned=" + bound);
                if (bound) {
                    latch.await(450, TimeUnit.MILLISECONDS);
                    line(out, "  callback=" + (result.length() == 0 ? "(none; probably not running/bindable)" : result.toString()));
                }
            } catch (Throwable t) {
                line(out, "  bind error=" + error(t));
            } finally {
                if (bound) {
                    try { context.unbindService(conn); } catch (Throwable ignored) {}
                }
            }
        }
    }

    static String startBroadcastSniffer(Context rawContext) {
        Context context = rawContext.getApplicationContext();
        synchronized (RECEIVER_LOCK) {
            stopBroadcastSnifferLocked();
            if (actionCandidates.isEmpty()) return "нет action-кандидатов из APK";
            IntentFilter filter = new IntentFilter();
            int count = 0;
            for (String action : actionCandidates) {
                try {
                    filter.addAction(action);
                    if (++count >= MAX_ACTIONS) break;
                } catch (Throwable ignored) {}
            }
            if (count == 0) return "нет валидных action-кандидатов";
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    StringBuilder row = new StringBuilder();
                    row.append("BROADCAST action=").append(intent == null ? "null" : intent.getAction());
                    if (intent != null) {
                        row.append(" package=").append(intent.getPackage());
                        row.append(" component=").append(intent.getComponent());
                        Bundle extras = intent.getExtras();
                        if (extras != null) {
                            row.append(" extras={");
                            int n = 0;
                            for (String k : extras.keySet()) {
                                if (n++ > 0) row.append(", ");
                                Object v;
                                try { v = extras.get(k); } catch (Throwable t) { v = "<err>"; }
                                row.append(k).append('=').append(value(v));
                                if (n >= 60) { row.append(", …"); break; }
                            }
                            row.append('}');
                        }
                    }
                    writeFile(context, "stock_broadcasts.log", timestamp() + " " + row + "\n", true);
                    context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
                            .putString("backend_last_broadcast", row.toString())
                            .putLong("backend_last_broadcast_ms", System.currentTimeMillis()).apply();
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(receiver, filter);
                receiverContext = context;
                return "активен; actions=" + count;
            } catch (Throwable t) {
                receiver = null;
                receiverContext = null;
                return "ошибка: " + error(t);
            }
        }
    }

    static void stopBroadcastSniffer() {
        synchronized (RECEIVER_LOCK) { stopBroadcastSnifferLocked(); }
    }

    private static void stopBroadcastSnifferLocked() {
        if (receiver != null && receiverContext != null) {
            try { receiverContext.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        }
        receiver = null;
        receiverContext = null;
    }

    private static String buildSummary(PackageInfo pi, int strings, int actions, String sniffer, String full) {
        int services = pi == null || pi.services == null ? 0 : pi.services.length;
        int exportedServices = 0;
        if (pi != null && pi.services != null) for (ServiceInfo s : pi.services) if (s.exported) exportedServices++;
        int providers = pi == null || pi.providers == null ? 0 : pi.providers.length;
        int exportedProviders = 0;
        if (pi != null && pi.providers != null) for (ProviderInfo p : pi.providers) if (p.exported) exportedProviders++;
        String binder = firstContaining(full, " -> ");
        return "package=" + (pi == null ? "NOT FOUND" : STOCK_PACKAGE)
                + "\nservices=" + services + " exported=" + exportedServices
                + "\nproviders=" + providers + " exported=" + exportedProviders
                + "\ninteresting APK strings=" + strings + " actions=" + actions
                + "\nsniffer=" + sniffer
                + (binder.isEmpty() ? "" : "\nBinder candidate: " + binder);
    }

    private static String firstContaining(String s, String needle) {
        for (String line : s.split("\\n")) if (line.contains(needle)) return line.trim();
        return "";
    }

    private static long packageVersionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static String protectionName(int level) {
        int base = level & PermissionInfo.PROTECTION_MASK_BASE;
        String b;
        switch (base) {
            case PermissionInfo.PROTECTION_NORMAL: b = "normal"; break;
            case PermissionInfo.PROTECTION_DANGEROUS: b = "dangerous"; break;
            case PermissionInfo.PROTECTION_SIGNATURE: b = "signature"; break;
            default: b = "0x" + Integer.toHexString(base); break;
        }
        return b + "/0x" + Integer.toHexString(level);
    }

    private static String value(Object v) {
        if (v == null) return "null";
        Class<?> c = v.getClass();
        if (!c.isArray()) return String.valueOf(v);
        int n = Array.getLength(v);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, 32); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.valueOf(Array.get(v, i)));
        }
        if (n > 32) sb.append(",…").append(n);
        return sb.append(']').toString();
    }

    private static String error(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getClass().getSimpleName() + ": " + safe(x.getMessage());
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void writeFile(Context context, String name, String text, boolean append) {
        try {
            File f = new File(context.getFilesDir(), name);
            try (FileWriter w = new FileWriter(f, append)) { w.write(text); }
        } catch (Throwable ignored) {}
    }

    private static void line(StringBuilder sb, String line) { sb.append(line).append('\n'); }
    private static String safe(String s) { return s == null ? "" : s; }

    static final class Result {
        final String summary;
        Result(String summary) { this.summary = summary; }
    }

    private StockBackendProbe() {}
}
