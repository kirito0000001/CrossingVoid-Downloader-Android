package com.lingjing.launcher.android;

import android.content.Context;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public final class LauncherLogStore {
    public static final long MAX_LOG_BYTES = 10L * 1024L * 1024L;
    private static final long TRIM_TARGET_BYTES = 8L * 1024L * 1024L;
    private static final String LOG_DIRECTORY = "logs";
    private static final String LOG_FILE_NAME = "launcher.log";
    private static final String PREFS_NAME = "crossingvoid_launcher_log";
    private static final String PREF_INSTALLATION_ID = "installation_id";
    private static final String UPLOAD_URL = "https://www.crossingvoid.top/api/launcher-diagnostics/upload-log";
    private static final Object FILE_LOCK = new Object();

    private LauncherLogStore() {
    }

    public static JSONObject append(Context context, String level, String event, String message, String details) throws IOException, JSONException {
        JSONObject entry = new JSONObject();
        entry.put("time", utcNow());
        entry.put("level", oneLine(level, 16));
        entry.put("event", oneLine(event, 96));
        entry.put("message", oneLine(message, 12000));
        String safeDetails = oneLine(details, 20000);
        if (!safeDetails.isBlank()) entry.put("details", safeDetails);
        byte[] lineBytes = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);

        synchronized (FILE_LOCK) {
            File logFile = getLogFile(context);
            if (logFile.length() + lineBytes.length > MAX_LOG_BYTES) {
                trimOldestLines(logFile);
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write(lineBytes);
            }
            return infoObject(context, logFile);
        }
    }

    public static JSONObject getInfo(Context context) throws IOException, JSONException {
        synchronized (FILE_LOCK) {
            return infoObject(context, getLogFile(context));
        }
    }

    public static JSONObject upload(Context context, String launcherVersion) throws IOException, JSONException {
        byte[] content;
        String installationId;
        synchronized (FILE_LOCK) {
            File logFile = getLogFile(context);
            if (!logFile.isFile() || logFile.length() <= 0L) {
                throw new IOException("当前没有可以上传的启动器日志。");
            }
            if (logFile.length() > MAX_LOG_BYTES) {
                trimOldestLines(logFile);
            }
            content = readAllBytes(logFile, MAX_LOG_BYTES);
            installationId = getInstallationId(context);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(content.length);
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        connection.setRequestProperty("X-Product-Key", "crossingvoid-android-launcher");
        connection.setRequestProperty("X-Installation-Id", installationId);
        connection.setRequestProperty("X-Launcher-Version", oneLine(launcherVersion, 48));
        try {
            try (OutputStream requestBody = connection.getOutputStream()) {
                requestBody.write(content);
                requestBody.flush();
            }
            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            String responseText = "";
            if (responseStream != null) {
                try (InputStream input = responseStream) {
                    responseText = new String(readStream(input, 64L * 1024L), StandardCharsets.UTF_8);
                }
            }
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("日志上传失败（HTTP " + statusCode + "）：" + oneLine(responseText, 300));
            }
            JSONObject result = new JSONObject();
            result.put("uploaded", true);
            result.put("sizeBytes", content.length);
            result.put("installationId", installationId);
            result.put("serverResponse", oneLine(responseText, 500));
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static File getLogFile(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), LOG_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建启动器日志目录。");
        }
        return new File(directory, LOG_FILE_NAME);
    }

    private static JSONObject infoObject(Context context, File logFile) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("fileName", LOG_FILE_NAME);
        result.put("sizeBytes", logFile.isFile() ? logFile.length() : 0L);
        result.put("maxBytes", MAX_LOG_BYTES);
        result.put("lastModified", logFile.isFile() ? logFile.lastModified() : 0L);
        result.put("hasLog", logFile.isFile() && logFile.length() > 0L);
        result.put("installationId", getInstallationId(context));
        return result;
    }

    private static String getInstallationId(Context context) {
        String existing = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_INSTALLATION_ID, "");
        if (existing != null && !existing.isBlank()) return existing;
        String created = UUID.randomUUID().toString();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_INSTALLATION_ID, created)
            .apply();
        return created;
    }

    private static void trimOldestLines(File logFile) throws IOException {
        if (!logFile.isFile() || logFile.length() <= TRIM_TARGET_BYTES) return;
        int keepLength = (int) Math.min(TRIM_TARGET_BYTES, logFile.length());
        byte[] tail = new byte[keepLength];
        long startOffset = logFile.length() - keepLength;
        try (RandomAccessFile input = new RandomAccessFile(logFile, "r")) {
            input.seek(startOffset);
            input.readFully(tail);
        }

        int firstCompleteLine = 0;
        if (startOffset > 0L) {
            while (firstCompleteLine < tail.length && tail[firstCompleteLine] != (byte) '\n') {
                firstCompleteLine++;
            }
            if (firstCompleteLine < tail.length) firstCompleteLine++;
        }

        File temporary = new File(logFile.getParentFile(), LOG_FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(tail, firstCompleteLine, tail.length - firstCompleteLine);
        }
        if (logFile.exists() && !logFile.delete()) {
            throw new IOException("无法整理启动器日志。");
        }
        if (!temporary.renameTo(logFile)) {
            throw new IOException("无法替换整理后的启动器日志。");
        }
    }

    private static byte[] readAllBytes(File file, long maximumBytes) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return readStream(input, maximumBytes);
        }
    }

    private static byte[] readStream(InputStream input, long maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            total += count;
            if (total > maximumBytes) throw new IOException("启动器日志超过允许大小。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String oneLine(String value, int maximumLength) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }
}
