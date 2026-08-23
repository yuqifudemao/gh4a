package com.gh4a.utils;

import android.content.Context;
import android.os.Build;
import com.gh4a.BuildConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DiagnosticLogger {
    private static final long MAX_LOG_SIZE = 512 * 1024;
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(github_pat_[a-z0-9_]+|gh[pousr]_[a-z0-9_]{20,}|(authorization\\s*[:=]\\s*)(bearer|token)\\s+\\S+)");
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static File sLogFile;

    private DiagnosticLogger() {}

    public static synchronized void init(Context context) {
        File directory = new File(context.getFilesDir(), "diagnostics");
        if (!directory.exists()) directory.mkdirs();
        sLogFile = new File(directory, "diagnostics.log");
        log("APP", "Started " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                + "), Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER
                + " " + Build.MODEL);
    }

    public static synchronized void log(String category, String message) {
        if (sLogFile == null) return;
        rotateIfNeeded();
        try (FileWriter writer = new FileWriter(sLogFile, true)) {
            writer.write(TIME_FORMAT.format(new Date()) + " [" + category + "] "
                    + sanitize(message) + "\n");
        } catch (IOException ignored) {}
    }

    public static void logThrowable(String category, Throwable error) {
        StringBuilder output = new StringBuilder();
        Throwable current = error;
        int causes = 0;
        while (current != null && causes++ < 5) {
            output.append(current.getClass().getName()).append(": ")
                    .append(current.getMessage()).append('\n');
            for (StackTraceElement element : current.getStackTrace()) {
                output.append("  at ").append(element).append('\n');
            }
            current = current.getCause();
            if (current != null) output.append("Caused by:\n");
        }
        log(category, output.toString());
    }

    public static synchronized String read() {
        if (sLogFile == null || !sLogFile.exists()) return "";
        try (FileInputStream input = new FileInputStream(sLogFile)) {
            byte[] data = new byte[(int) sLogFile.length()];
            int count = input.read(data);
            return count > 0 ? sanitize(new String(data, 0, count, StandardCharsets.UTF_8)) : "";
        } catch (IOException e) {
            return "Unable to read diagnostic log: " + e.getMessage();
        }
    }

    public static synchronized File createShareFile(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "diagnostics");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create diagnostics cache directory");
        }
        File output = new File(directory, "OctoDroid-diagnostics.txt");
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(read().getBytes(StandardCharsets.UTF_8));
        }
        return output;
    }

    public static synchronized void clear() {
        if (sLogFile != null && sLogFile.exists()) sLogFile.delete();
        log("APP", "Diagnostic log cleared");
    }

    private static String sanitize(String value) {
        return value == null ? "(no message)"
                : TOKEN_PATTERN.matcher(value).replaceAll("[REDACTED]");
    }

    private static void rotateIfNeeded() {
        if (sLogFile == null || !sLogFile.exists() || sLogFile.length() < MAX_LOG_SIZE) return;
        File oldFile = new File(sLogFile.getParentFile(), "diagnostics.old.log");
        oldFile.delete();
        sLogFile.renameTo(oldFile);
    }
}
