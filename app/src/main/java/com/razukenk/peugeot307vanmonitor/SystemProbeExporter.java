package com.razukenk.peugeot307vanmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SystemProbeExporter {
    private static final long MAX_APK = 80L * 1024L * 1024L;
    private static final long MAX_TOTAL = 180L * 1024L * 1024L;
    private static final int MAX_APKS = 16;

    static Uri export(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "peugeot307_system_probe_" + stamp + ".zip";

        Uri uri;
        OutputStream rawOut;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Peugeot307VanMonitor");
            ContentResolver resolver = context.getContentResolver();
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Cannot create probe ZIP");
            rawOut = resolver.openOutputStream(uri);
        } else {
            File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "Peugeot307VanMonitor");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create export directory");
            File outFile = new File(dir, filename);
            uri = Uri.fromFile(outFile);
            rawOut = new java.io.FileOutputStream(outFile);
        }

        if (rawOut == null) throw new IllegalStateException("Cannot open probe ZIP");

        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String lastScreenPackage = prefs.getString("screen_package", "");

        StringBuilder report = new StringBuilder();
        report.append("Peugeot 307 CarInfo system probe v0.4\n");
        report.append("Generated: ").append(new Date()).append("\n\n");
        report.append("[DEVICE]\n");
        report.append("Manufacturer=").append(Build.MANUFACTURER).append('\n');
        report.append("Brand=").append(Build.BRAND).append('\n');
        report.append("Model=").append(Build.MODEL).append('\n');
        report.append("Device=").append(Build.DEVICE).append('\n');
        report.append("Product=").append(Build.PRODUCT).append('\n');
        report.append("Android=").append(Build.VERSION.RELEASE).append(" SDK=").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Fingerprint=").append(Build.FINGERPRINT).append('\n');
        report.append("Last CANBUS screen package=").append(lastScreenPackage).append("\n\n");

        report.append("[/DEV CANDIDATES]\n");
        File dev = new File("/dev");
        File[] devFiles = dev.listFiles();
        if (devFiles != null) {
            for (File f : devFiles) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.contains("tty") || n.contains("uart") || n.contains("serial") ||
                        n.contains("can") || n.contains("mcu")) {
                    report.append(f.getAbsolutePath())
                            .append(" r=").append(f.canRead())
                            .append(" w=").append(f.canWrite())
                            .append(" len=").append(f.length())
                            .append('\n');
                }
            }
        }

        report.append("\n[SYSTEM APP PATHS]\n");
        String[] roots = {"/system/app", "/system/priv-app", "/system_ext/app", "/product/app", "/vendor/app"};
        for (String root : roots) listPaths(new File(root), report, 0, 3);

        report.append("\n[INSTALLED PACKAGE CANDIDATES]\n");
        List<ApplicationInfo> candidates = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Throwable t) {
                label = "";
            }
            String hay = (ai.packageName + " " + label).toLowerCase(Locale.US);
            boolean candidate = ai.packageName.equals(lastScreenPackage) ||
                    hay.contains("canbus") || hay.contains("can box") || hay.contains("mcu") ||
                    hay.contains("vehicle") || hay.contains("car info") || hay.contains("carinfo") ||
                    hay.contains("factory") || hay.contains("radio") || hay.contains("simple") ||
                    hay.contains("protocol") || hay.contains("decoder");
            if (!candidate) continue;

            candidates.add(ai);
            report.append("\npackage=").append(ai.packageName).append('\n');
            report.append("label=").append(label).append('\n');
            report.append("uid=").append(ai.uid).append('\n');
            report.append("sourceDir=").append(ai.sourceDir).append('\n');
            report.append("nativeLibraryDir=").append(ai.nativeLibraryDir).append('\n');
            report.append("flags=0x").append(Integer.toHexString(ai.flags)).append('\n');

            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName,
                        PackageManager.GET_SERVICES |
                        PackageManager.GET_RECEIVERS |
                        PackageManager.GET_PROVIDERS |
                        PackageManager.GET_ACTIVITIES);

                if (pi.activities != null) {
                    report.append("activities:\n");
                    for (android.content.pm.ActivityInfo x : pi.activities) report.append("  ").append(x.name).append(" exported=").append(x.exported).append('\n');
                }
                if (pi.services != null) {
                    report.append("services:\n");
                    for (android.content.pm.ServiceInfo x : pi.services) report.append("  ").append(x.name).append(" exported=").append(x.exported).append(" permission=").append(x.permission).append('\n');
                }
                if (pi.receivers != null) {
                    report.append("receivers:\n");
                    for (android.content.pm.ActivityInfo x : pi.receivers) report.append("  ").append(x.name).append(" exported=").append(x.exported).append(" permission=").append(x.permission).append('\n');
                }
                if (pi.providers != null) {
                    report.append("providers:\n");
                    for (android.content.pm.ProviderInfo x : pi.providers) report.append("  ").append(x.name).append(" auth=").append(x.authority).append(" exported=").append(x.exported).append('\n');
                }
            } catch (Throwable t) {
                report.append("packageInfoError=").append(t).append('\n');
            }
        }

        try (ZipOutputStream zip = new ZipOutputStream(rawOut)) {
            putText(zip, "system_probe.txt", report.toString());

            long total = 0;
            int count = 0;
            for (ApplicationInfo ai : candidates) {
                if (count >= MAX_APKS || total >= MAX_TOTAL) break;
                try {
                    File apk = new File(ai.sourceDir);
                    if (!apk.isFile() || !apk.canRead()) continue;
                    long size = apk.length();
                    if (size <= 0 || size > MAX_APK || total + size > MAX_TOTAL) continue;

                    String entryName = "apks/" + safe(ai.packageName) + ".apk";
                    zip.putNextEntry(new ZipEntry(entryName));
                    try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(apk))) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) >= 0) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                    total += size;
                    count++;
                } catch (Throwable ignored) {
                }
            }

            putText(zip, "summary.txt",
                    "Candidate APKs copied: " + count + "\n" +
                    "Copied bytes: " + total + "\n" +
                    "Upload this ZIP to the chat for analysis.\n");
        }

        return uri;
    }

    private static void listPaths(File dir, StringBuilder out, int depth, int maxDepth) {
        if (dir == null || depth > maxDepth || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            out.append(f.getAbsolutePath());
            if (f.isFile()) out.append(" size=").append(f.length());
            out.append('\n');
            if (f.isDirectory()) listPaths(f, out, depth + 1, maxDepth);
        }
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private SystemProbeExporter() {}
}
