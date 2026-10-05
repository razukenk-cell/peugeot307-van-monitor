package com.razukenk.peugeot307vanmonitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CanbusApkExporter {
    static final class Result {
        final int copied;
        final String folder;
        final String details;

        Result(int copied, String folder, String details) {
            this.copied = copied;
            this.folder = folder;
            this.details = details;
        }
    }

    private static final class Candidate {
        final ApplicationInfo app;
        final String label;
        final int score;

        Candidate(ApplicationInfo app, String label, int score) {
            this.app = app;
            this.label = label;
            this.score = score;
        }
    }

    static Result findAndCopy(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        PackageManager pm = context.getPackageManager();
        String lastScreenPackage = prefs.getString("screen_package", "");
        if (lastScreenPackage == null) lastScreenPackage = "";

        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<Candidate> candidates = new ArrayList<>();

        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(context.getPackageName())) continue;

            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Throwable t) {
                label = "";
            }

            String source = ai.sourceDir == null ? "" : ai.sourceDir;
            String hay = (ai.packageName + " " + label + " " + source).toLowerCase(Locale.US);

            int score = 0;
            if (!lastScreenPackage.isEmpty() && ai.packageName.equals(lastScreenPackage)) score += 1000;
            if (hay.contains("canbus")) score += 300;
            if (hay.contains("can box") || hay.contains("canbox")) score += 250;
            if (hay.contains("mcu")) score += 220;
            if (hay.contains("vehicle")) score += 180;
            if (hay.contains("protocol")) score += 170;
            if (hay.contains("decoder")) score += 160;
            if (hay.contains("simple")) score += 120;
            if (hay.contains("factory")) score += 80;
            if (hay.contains("carinfo") || hay.contains("car info")) score += 80;
            if (hay.contains("car") && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) score += 30;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) score += 10;

            if (score >= 80 || (!lastScreenPackage.isEmpty() && ai.packageName.equals(lastScreenPackage))) {
                candidates.add(new Candidate(ai, label, score));
            }
        }

        candidates.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed());

        String relative = Environment.DIRECTORY_DOWNLOADS + "/Peugeot307VanMonitor/CANBUS_APK";
        String folderText = "Downloads/Peugeot307VanMonitor/CANBUS_APK";
        StringBuilder manifest = new StringBuilder();
        manifest.append("Peugeot 307 CarInfo CANBUS APK export v0.5\n");
        manifest.append("Detected CANBUS screen package: ").append(lastScreenPackage).append("\n\n");

        int copied = 0;
        Set<String> copiedPackages = new HashSet<>();

        for (Candidate c : candidates) {
            if (copiedPackages.size() >= 8) break;

            manifest.append("Candidate score=").append(c.score)
                    .append(" package=").append(c.app.packageName)
                    .append(" label=").append(c.label)
                    .append(" source=").append(c.app.sourceDir)
                    .append("\n");

            boolean packageCopied = false;

            if (copyApk(context, c.app.sourceDir,
                    safe(c.app.packageName) + "__base.apk", relative)) {
                copied++;
                packageCopied = true;
            }

            if (c.app.splitSourceDirs != null) {
                for (int i = 0; i < c.app.splitSourceDirs.length; i++) {
                    if (copyApk(context, c.app.splitSourceDirs[i],
                            safe(c.app.packageName) + "__split" + i + ".apk", relative)) {
                        copied++;
                        packageCopied = true;
                    }
                }
            }

            manifest.append("  copied=").append(packageCopied).append("\n");
            if (packageCopied) copiedPackages.add(c.app.packageName);
        }

        if (candidates.isEmpty()) {
            manifest.append("No strong package candidates found.\n");
        }

        writeText(context, "CANBUS_APK_candidates.txt", manifest.toString(), relative);

        String details = "Скопировано APK-файлов: " + copied +
                "\nПапка: " + folderText +
                "\nКандидатов приложений: " + candidates.size();

        prefs.edit()
                .putString("apk_export_result", details)
                .putLong("apk_export_time", System.currentTimeMillis())
                .apply();

        return new Result(copied, folderText, details);
    }

    private static boolean copyApk(Context context, String sourcePath,
                                   String displayName, String relative) {
        if (sourcePath == null || sourcePath.isEmpty()) return false;

        File src = new File(sourcePath);
        if (!src.isFile() || !src.canRead() || src.length() <= 0) return false;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                values.put(MediaStore.Downloads.RELATIVE_PATH, relative);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;

                try (OutputStream out = resolver.openOutputStream(uri);
                     BufferedInputStream in = new BufferedInputStream(new FileInputStream(src))) {
                    if (out == null) return false;
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }

                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return true;
            }

            File root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (root == null) return false;
            File dir = new File(root, "Peugeot307VanMonitor/CANBUS_APK");
            if (!dir.exists() && !dir.mkdirs()) return false;

            File outFile = new File(dir, displayName);
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src));
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeText(Context context, String name, String text, String relative) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, relative);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Cannot write candidate list");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open candidate list");
                out.write(data);
            }
            return;
        }

        File root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) return;
        File dir = new File(root, "Peugeot307VanMonitor/CANBUS_APK");
        if (!dir.exists()) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private CanbusApkExporter() {}
}
