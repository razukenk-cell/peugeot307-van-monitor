package com.razukenk.peugeot307vanmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Locale;

/**
 * v0.10 safety probe for /dev/ttyCanbus.
 *
 * IMPORTANT: this class intentionally never opens the device node. It only
 * performs metadata/access checks (stat/lstat/access/canonical path). There is
 * no FileInputStream, FileOutputStream, ParcelFileDescriptor, read(), write(),
 * ioctl(), termios or native serial call here.
 */
final class DeviceNodeProbe {
    static final String DEVICE = "/dev/ttyCanbus";

    static Result run(Context context) {
        SharedPreferences p = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        File file = new File(DEVICE);

        boolean exists = false;
        boolean fileCanRead = false;
        boolean fileCanWrite = false;
        boolean accessRead = false;
        boolean accessWrite = false;
        String canonical = "(unknown)";
        String statType = "(stat unavailable)";
        String mode = "(unknown)";
        int uid = -1;
        int gid = -1;
        String statError = "(none)";
        String accessReadError = "(none)";
        String accessWriteError = "(none)";
        String selinux = "(unavailable)";
        String selinuxError = "(none)";

        try { exists = file.exists(); } catch (Throwable t) { statError = error(t); }
        try { fileCanRead = file.canRead(); } catch (Throwable t) { accessReadError = error(t); }
        try { fileCanWrite = file.canWrite(); } catch (Throwable t) { accessWriteError = error(t); }
        try { canonical = file.getCanonicalPath(); } catch (Throwable t) { canonical = DEVICE + " (canonical error: " + error(t) + ")"; }
        try { accessRead = Os.access(DEVICE, OsConstants.R_OK); } catch (Throwable t) { accessReadError = error(t); }
        try { accessWrite = Os.access(DEVICE, OsConstants.W_OK); } catch (Throwable t) { accessWriteError = error(t); }

        try {
            StructStat st = Os.lstat(DEVICE);
            uid = st.st_uid;
            gid = st.st_gid;
            mode = String.format(Locale.US, "0%04o", st.st_mode & 07777);
            statType = fileType(st.st_mode);
        } catch (Throwable t) { statError = error(t); }

        try {
            Class<?> cls = Class.forName("android.os.SELinux");
            Method method = cls.getDeclaredMethod("getFileContext", String.class);
            method.setAccessible(true);
            Object value = method.invoke(null, DEVICE);
            if (value != null) selinux = String.valueOf(value);
        } catch (Throwable t) { selinuxError = error(t); }

        String verdict;
        if (!exists) verdict = "DEVICE NOT FOUND";
        else if (accessRead || fileCanRead) verdict = "READ PERMISSION LOOKS AVAILABLE (metadata only; device was NOT opened)";
        else verdict = "READ ACCESS DENIED/UNAVAILABLE (metadata probe)";

        String summary =
                "Путь: " + DEVICE + "\n" +
                "exists: " + exists + "\n" +
                "type: " + statType + "\n" +
                "mode: " + mode + "  uid: " + uid + "  gid: " + gid + "\n" +
                "File.canRead(): " + fileCanRead + "\n" +
                "Os.access(R_OK): " + accessRead + "\n" +
                "File.canWrite(): " + fileCanWrite + "\n" +
                "Os.access(W_OK): " + accessWrite + "\n" +
                "SELinux: " + selinux + "\n" +
                "OPEN attempted: FALSE\nREAD attempted: FALSE\nWRITE attempted: FALSE\n" +
                "Итог: " + verdict;

        p.edit()
                .putLong("tty_probe_time_ms", now)
                .putString("tty_probe_time", new Date(now).toString())
                .putString("tty_probe_path", DEVICE)
                .putBoolean("tty_probe_exists", exists)
                .putString("tty_probe_canonical", canonical)
                .putString("tty_probe_type", statType)
                .putString("tty_probe_mode", mode)
                .putInt("tty_probe_uid", uid)
                .putInt("tty_probe_gid", gid)
                .putBoolean("tty_probe_file_can_read", fileCanRead)
                .putBoolean("tty_probe_file_can_write", fileCanWrite)
                .putBoolean("tty_probe_access_read", accessRead)
                .putBoolean("tty_probe_access_write", accessWrite)
                .putString("tty_probe_stat_error", statError)
                .putString("tty_probe_access_read_error", accessReadError)
                .putString("tty_probe_access_write_error", accessWriteError)
                .putString("tty_probe_selinux", selinux)
                .putString("tty_probe_selinux_error", selinuxError)
                .putString("tty_probe_verdict", verdict)
                .putString("tty_probe_summary", summary)
                .putBoolean("tty_probe_open_attempted", false)
                .putBoolean("tty_probe_read_attempted", false)
                .putBoolean("tty_probe_write_attempted", false)
                .apply();

        return new Result(summary, verdict);
    }

    static String summary(SharedPreferences p) {
        return p.getString("tty_probe_summary", "Проверка ещё не запускалась.\n" + "v0.10 НЕ открывает /dev/ttyCanbus и ничего не читает/не пишет.");
    }

    private static String fileType(int stMode) {
        int type = stMode & 0170000;
        if (type == 0020000) return "character device";
        if (type == 0060000) return "block device";
        if (type == 0100000) return "regular file";
        if (type == 0040000) return "directory";
        if (type == 0120000) return "symlink";
        if (type == 0010000) return "fifo";
        if (type == 0140000) return "socket";
        return String.format(Locale.US, "unknown(0%o)", type);
    }

    private static String error(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String message = x.getMessage();
        return x.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    static final class Result {
        final String summary;
        final String verdict;
        Result(String summary, String verdict) { this.summary = summary; this.verdict = verdict; }
    }

    private DeviceNodeProbe() {}
}
