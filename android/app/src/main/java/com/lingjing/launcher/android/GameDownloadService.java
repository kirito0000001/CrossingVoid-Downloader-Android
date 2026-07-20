package com.lingjing.launcher.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GameDownloadService extends Service {
    public static final String ACTION_START = "com.lingjing.launcher.android.action.START_DOWNLOAD";
    public static final String ACTION_PAUSE = "com.lingjing.launcher.android.action.PAUSE_DOWNLOAD";
    public static final String ACTION_CANCEL = "com.lingjing.launcher.android.action.CANCEL_DOWNLOAD";
    public static final String ACTION_STATE = "com.lingjing.launcher.android.action.DOWNLOAD_STATE";
    public static final String EXTRA_PLAN = "downloadPlan";
    public static final String EXTRA_STATE = "downloadState";

    private static final String UPDATE_API = "https://www.crossingvoid.top/api/toolbox-updates/sign-download";
    private static final String PREFS_NAME = "crossingvoid_download";
    private static final String PREF_STATE = "state";
    private static final String PREF_PLAN = "plan";
    private static final String PREF_MANAGED_VERSION = "managedVersion";
    private static final String CHANNEL_ID = "crossingvoid_game_download";
    private static final int NOTIFICATION_ID = 2014;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final long STATE_INTERVAL_MS = 350L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean PAUSE_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);
    private static final Object STATE_LOCK = new Object();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long lastStateAt;
    private long lastRateBytes;
    private long lastRateAt;
    private double bytesPerSecond;
    private Plan activePlan;
    private int verifiedChunks;
    private String lastLoggedStateSignature = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            PAUSE_REQUESTED.set(true);
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            CANCEL_REQUESTED.set(true);
            PAUSE_REQUESTED.set(false);
            if (!RUNNING.get()) {
                clearAllDownloads(this);
                saveAndBroadcastState(idleState());
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        String planJson = intent == null ? null : intent.getStringExtra(EXTRA_PLAN);
        if (planJson == null || planJson.isBlank()) {
            planJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PLAN, "");
        }
        if (planJson == null || planJson.isBlank()) {
            saveAndBroadcastState(errorState("没有可恢复的下载任务。"));
            stopSelf();
            return START_NOT_STICKY;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_PLAN, planJson).apply();
        PAUSE_REQUESTED.set(false);
        CANCEL_REQUESTED.set(false);
        startForegroundCompat(buildNotification(0, false));
        if (RUNNING.compareAndSet(false, true)) {
            String finalPlanJson = planJson;
            executor.execute(() -> runDownload(finalPlanJson));
        }
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void runDownload(String planJson) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossingVoidLauncher:GameDownload");
            wakeLock.acquire(6L * 60L * 60L * 1000L);

            activePlan = Plan.parse(planJson);
            File downloadsRoot = getDownloadsRoot(this);
            File workDir = new File(downloadsRoot, "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            File archiveFile = new File(workDir, activePlan.archiveFileName);
            ensureDirectory(chunksDir);
            prepareForPlan(downloadsRoot, workDir, activePlan);

            JSONObject previous = readStateObject(this);
            verifiedChunks = activePlan.matchesState(previous) ? previous.optInt("verifiedChunks", 0) : 0;
            verifiedChunks = Math.max(0, Math.min(verifiedChunks, activePlan.chunks.size()));
            long existingBytes = existingChunkBytes(activePlan, chunksDir);
            long requiredBytes = requiredAvailableBytes(activePlan, archiveFile, existingBytes);
            long availableBytes = new StatFs(downloadsRoot.getAbsolutePath()).getAvailableBytes();
            if (availableBytes < requiredBytes) {
                throw new IOException("存储空间不足：还需要 " + formatBytes(requiredBytes) + "，当前可用 " + formatBytes(availableBytes));
            }

            if (archiveFile.length() != activePlan.totalBytes) {
                downloadChunks(activePlan, chunksDir);
                checkControlSignals();
                mergeChunks(activePlan, chunksDir, archiveFile);
            }
            checkControlSignals();
            verifyArchive(activePlan, archiveFile);
            checkControlSignals();
            PreparedFiles prepared = extractPackage(activePlan, archiveFile, downloadsRoot);
            deleteRecursively(workDir);
            publishState("ready", "APK 和 OBB 已准备完成", activePlan.totalBytes, 100.0, activePlan.chunks.size(), true, prepared);
            showCompletionNotification();
        } catch (PausedException ignored) {
            publishState("paused", "下载已暂停，稍后可以继续", downloadedBytes(), currentPercent(), currentChunkIndex(), true, null);
            showPausedNotification();
        } catch (CancelledException ignored) {
            clearAllDownloads(this);
            saveAndBroadcastState(idleState());
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        } catch (Exception error) {
            String message = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
            publishState("error", message, downloadedBytes(), currentPercent(), currentChunkIndex(), true, null);
            showErrorNotification(message);
        } finally {
            RUNNING.set(false);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            stopForeground(false);
            stopSelf();
        }
    }

    private void downloadChunks(Plan plan, File chunksDir) throws Exception {
        for (int position = 0; position < plan.chunks.size(); position++) {
            checkControlSignals();
            Chunk chunk = plan.chunks.get(position);
            File file = new File(chunksDir, chunk.fileName);

            if (position < verifiedChunks && file.length() == chunk.sizeBytes) {
                continue;
            }
            if (file.length() == chunk.sizeBytes && hashMatches(file, chunk.sha256)) {
                verifiedChunks = position + 1;
                publishDownloadProgress("已校验第 " + chunk.index + " / " + chunk.count + " 片", true);
                continue;
            }
            if (file.length() > chunk.sizeBytes || file.length() == chunk.sizeBytes) {
                deleteFile(file);
            }

            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                checkControlSignals();
                try {
                    downloadChunk(plan, chunk, file);
                    if (file.length() != chunk.sizeBytes) {
                        throw new IOException("第 " + chunk.index + " 片大小不正确");
                    }
                    publishState("verifying", "正在校验第 " + chunk.index + " / " + chunk.count + " 片", downloadedBytes(), currentPercent(), chunk.index, true, null);
                    if (!hashMatches(file, chunk.sha256)) {
                        deleteFile(file);
                        throw new IOException("第 " + chunk.index + " 片校验失败");
                    }
                    verifiedChunks = position + 1;
                    publishDownloadProgress("第 " + chunk.index + " / " + chunk.count + " 片下载完成", true);
                    lastError = null;
                    break;
                } catch (PausedException | CancelledException control) {
                    throw control;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt < MAX_ATTEMPTS) {
                        publishDownloadProgress("第 " + chunk.index + " 片连接中断，正在重试 " + (attempt + 1) + " / " + MAX_ATTEMPTS, true);
                        sleepWithControl(500L * attempt);
                    }
                }
            }
            if (lastError != null) {
                throw new IOException("第 " + chunk.index + " 片下载失败：" + lastError.getMessage(), lastError);
            }
        }
    }

    private void downloadChunk(Plan plan, Chunk chunk, File outputFile) throws Exception {
        String officialUrl = plan.source.equals("official") ? signChunkUrl(plan, chunk) : "";
        String downloadUrl = DownloadFileUtils.resolveDownloadUrl(plan.source, chunk.downloadUrl, officialUrl);
        long resumeFrom = outputFile.exists() ? outputFile.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "CrossingVoidAndroidLauncher/" + currentLauncherVersion());
        connection.setRequestProperty("Accept", "application/octet-stream");
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        }

        try {
            int status = connection.getResponseCode();
            if (status == 416 && resumeFrom == chunk.sizeBytes) {
                return;
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("下载服务器返回 HTTP " + status);
            }
            if (resumeFrom > 0 && status != HttpURLConnection.HTTP_PARTIAL) {
                resumeFrom = 0;
            }

            ensureDirectory(outputFile.getParentFile());
            try (
                InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                RandomAccessFile output = new RandomAccessFile(outputFile, "rw")
            ) {
                if (resumeFrom == 0) {
                    output.setLength(0);
                }
                output.seek(resumeFrom);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkControlSignals();
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    publishDownloadProgress("正在下载第 " + chunk.index + " / " + chunk.count + " 片", false);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private String signChunkUrl(Plan plan, Chunk chunk) throws Exception {
        JSONObject request = new JSONObject();
        request.put("productKey", plan.productKey);
        request.put("version", plan.version);
        request.put("runtime", plan.runtime);
        request.put("objectKey", chunk.objectKey);
        request.put("launcherVersion", currentLauncherVersion());

        HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_API).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "CrossingVoidAndroidLauncher/" + currentLauncherVersion());
        try {
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw new IOException("签名服务器返回 HTTP " + status + "：" + response);
            }
            JSONObject payload = new JSONObject(response);
            if (!payload.optBoolean("success", false) || payload.optString("url").isBlank()) {
                throw new IOException(payload.optString("message", "无法获取下载地址"));
            }
            return payload.getString("url");
        } finally {
            connection.disconnect();
        }
    }

    private void mergeChunks(Plan plan, File chunksDir, File archiveFile) throws Exception {
        publishState("merging", "正在合并下载分片", plan.totalBytes, 85.0, plan.chunks.size(), true, null);
        File temporary = new File(archiveFile.getParentFile(), archiveFile.getName() + ".merging");
        deleteFile(temporary);
        long copied = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), BUFFER_SIZE)) {
            for (Chunk chunk : plan.chunks) {
                checkControlSignals();
                File part = new File(chunksDir, chunk.fileName);
                if (part.length() != chunk.sizeBytes) {
                    throw new IOException("合并前发现第 " + chunk.index + " 片不完整");
                }
                try (InputStream input = new BufferedInputStream(new FileInputStream(part), BUFFER_SIZE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        checkControlSignals();
                        if (read == 0) {
                            continue;
                        }
                        output.write(buffer, 0, read);
                        copied += read;
                        double percent = 85.0 + (copied / (double) plan.totalBytes) * 5.0;
                        publishState("merging", "正在合并第 " + chunk.index + " / " + chunk.count + " 片", plan.totalBytes, percent, chunk.index, false, null);
                    }
                }
                deleteFile(part);
            }
        }
        if (temporary.length() != plan.totalBytes) {
            throw new IOException("合并后的完整包大小不正确");
        }
        deleteFile(archiveFile);
        if (!temporary.renameTo(archiveFile)) {
            throw new IOException("无法保存合并后的完整包");
        }
    }

    private void verifyArchive(Plan plan, File archiveFile) throws Exception {
        publishState("verifying", "正在校验完整安装包", plan.totalBytes, 90.0, plan.chunks.size(), true, null);
        String actual = sha256WithProgress(archiveFile, 90.0, 5.0);
        if (!actual.equalsIgnoreCase(plan.archiveSha256)) {
            deleteFile(archiveFile);
            throw new IOException("完整安装包 SHA256 校验失败，请重新下载");
        }
    }

    private PreparedFiles extractPackage(Plan plan, File archiveFile, File downloadsRoot) throws Exception {
        publishState("extracting", "正在解压 APK 和 OBB", plan.totalBytes, 95.0, plan.chunks.size(), true, null);
        File preparedDir = new File(downloadsRoot, "prepared");
        File obbDir = getObbDir();
        if (obbDir == null) {
            throw new IOException("系统没有提供可用的游戏 OBB 目录");
        }
        ensureDirectory(obbDir);
        File apkOutput = new File(preparedDir, "CrossingVoid-latest.apk");
        File obbOutput = null;
        long extracted = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archiveFile), BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkControlSignals();
                String name = entry.getName().replace('\\', '/');
                if (!DownloadFileUtils.isSafeZipEntry(name)) {
                    throw new IOException("安装包包含不安全路径：" + name);
                }
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                File target = null;
                if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    target = apkOutput;
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".obb")) {
                    target = new File(obbDir, new File(name).getName());
                    obbOutput = target;
                }
                if (target == null) {
                    zip.closeEntry();
                    continue;
                }

                ensureDirectory(target.getParentFile());
                File temporary = new File(target.getParentFile(), target.getName() + ".extracting");
                deleteFile(temporary);
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), BUFFER_SIZE)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        checkControlSignals();
                        if (read == 0) {
                            continue;
                        }
                        output.write(buffer, 0, read);
                        extracted += read;
                        double percent = 95.0 + Math.min(1.0, extracted / (double) plan.totalBytes) * 5.0;
                        publishState("extracting", "正在解压 " + new File(name).getName(), plan.totalBytes, percent, plan.chunks.size(), false, null);
                    }
                }
                deleteFile(target);
                if (!temporary.renameTo(target)) {
                    throw new IOException("无法保存 " + target.getName());
                }
                zip.closeEntry();
            }
        }
        if (!apkOutput.isFile() || apkOutput.length() <= 0) {
            throw new IOException("安装包内没有找到 APK");
        }
        if (obbOutput == null || !obbOutput.isFile() || obbOutput.length() <= 0) {
            throw new IOException("安装包内没有找到 OBB");
        }
        removeStaleObbFiles(obbDir, obbOutput);
        return new PreparedFiles(apkOutput, obbOutput, UUID.randomUUID().toString());
    }

    private void removeStaleObbFiles(File obbDir, File currentObb) {
        File[] files = obbDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.equals(currentObb) && file.isFile() && (name.startsWith("main.") || name.startsWith("patch.")) && name.endsWith(".obb")) {
                file.delete();
            }
        }
    }

    private String sha256WithProgress(File file, double basePercent, double spanPercent) throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前设备不支持 SHA-256", error);
        }
        long processed = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkControlSignals();
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                processed += read;
                double percent = basePercent + Math.min(1.0, processed / (double) Math.max(1L, file.length())) * spanPercent;
                publishState("verifying", "正在校验完整安装包", activePlan.totalBytes, percent, activePlan.chunks.size(), false, null);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private void publishDownloadProgress(String message, boolean force) {
        long downloaded = downloadedBytes();
        double percent = activePlan == null || activePlan.totalBytes <= 0 ? 0.0 : (downloaded / (double) activePlan.totalBytes) * 85.0;
        publishState("downloading", message, downloaded, percent, currentChunkIndex(), force, null);
    }

    private void publishState(String status, String message, long downloadedBytes, double percent, int currentChunk, boolean force, PreparedFiles prepared) {
        long now = System.currentTimeMillis();
        if (!force && now - lastStateAt < STATE_INTERVAL_MS) {
            return;
        }
        lastStateAt = now;
        updateTransferRate(status, downloadedBytes, now);
        JSONObject state = new JSONObject();
        try {
            state.put("status", status);
            state.put("message", message);
            state.put("version", activePlan == null ? "" : activePlan.version);
            state.put("source", activePlan == null ? "" : activePlan.source);
            state.put("archiveSha256", activePlan == null ? "" : activePlan.archiveSha256);
            state.put("downloadedBytes", Math.max(0L, downloadedBytes));
            state.put("totalBytes", activePlan == null ? 0L : activePlan.totalBytes);
            state.put("percent", Math.max(0.0, Math.min(100.0, percent)));
            state.put("currentChunk", Math.max(0, currentChunk));
            state.put("totalChunks", activePlan == null ? 0 : activePlan.chunks.size());
            state.put("verifiedChunks", verifiedChunks);
            state.put("bytesPerSecond", bytesPerSecond);
            state.put("canPause", status.equals("downloading"));
            state.put("updatedAt", now);
            if (prepared != null) {
                state.put("apkPath", prepared.apk.getAbsolutePath());
                state.put("obbPath", prepared.obb.getAbsolutePath());
                state.put("obbFileName", prepared.obb.getName());
                state.put("installToken", prepared.installToken);
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        String logSignature = status + "|" + currentChunk + "|" + verifiedChunks + "|" + message;
        if (!logSignature.equals(lastLoggedStateSignature)) {
            lastLoggedStateSignature = logSignature;
            String level = status.equals("error") ? "error" : "info";
            try {
                LauncherLogStore.append(this, level, "game-download.state", message, state.toString());
            } catch (Exception ignored) {
            }
        }
        saveAndBroadcastState(state);
        if (status.equals("downloading") || status.equals("verifying") || status.equals("merging") || status.equals("extracting")) {
            updateNotification((int) Math.round(percent), status.equals("downloading"));
        }
    }

    private void updateTransferRate(String status, long downloadedBytes, long now) {
        if (!status.equals("downloading")) {
            bytesPerSecond = 0.0;
            lastRateBytes = Math.max(0L, downloadedBytes);
            lastRateAt = now;
            return;
        }
        if (lastRateAt > 0L && now > lastRateAt && downloadedBytes >= lastRateBytes) {
            double instantaneous = (downloadedBytes - lastRateBytes) * 1000.0 / (now - lastRateAt);
            if (instantaneous > 0.0) {
                bytesPerSecond = bytesPerSecond <= 0.0
                    ? instantaneous
                    : bytesPerSecond * 0.65 + instantaneous * 0.35;
            }
        }
        lastRateBytes = Math.max(0L, downloadedBytes);
        lastRateAt = now;
    }

    private void saveAndBroadcastState(JSONObject state) {
        writeStateAndBroadcast(this, state);
    }

    private static void writeStateAndBroadcast(Context context, JSONObject state) {
        String json = state.toString();
        synchronized (STATE_LOCK) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_STATE, json).apply();
        }
        Intent update = new Intent(ACTION_STATE);
        update.setPackage(context.getPackageName());
        update.putExtra(EXTRA_STATE, json);
        context.sendBroadcast(update);
    }

    public static JSONObject readStateObject(Context context) {
        synchronized (STATE_LOCK) {
            String json = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_STATE, "");
            if (json == null || json.isBlank()) {
                return idleState();
            }
            try {
                return new JSONObject(json);
            } catch (JSONException ignored) {
                return errorState("下载状态文件已损坏，请重新开始下载。");
            }
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String getManagedVersion(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_MANAGED_VERSION, "");
    }

    public static void clearManagedVersion(Context context) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_MANAGED_VERSION).apply();
    }

    public static boolean completeInstallation(Context context, String installToken) {
        JSONObject state = readStateObject(context);
        if (installToken == null || !installToken.equals(state.optString("installToken"))) {
            return false;
        }
        String version = state.optString("version", "");
        deleteRecursively(new File(getDownloadsRoot(context), "prepared"));
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(PREF_STATE)
            .remove(PREF_PLAN)
            .putString(PREF_MANAGED_VERSION, version)
            .apply();
        JSONObject completed = idleState();
        try {
            completed.put("message", "游戏资源安装完成");
        } catch (JSONException ignored) {
        }
        writeStateAndBroadcast(context, completed);
        return true;
    }

    public static boolean failInstallation(Context context, String installToken, String failureMessage) {
        JSONObject state = readStateObject(context);
        if (installToken == null || !installToken.equals(state.optString("installToken"))) {
            return false;
        }
        try {
            state.put("status", "error");
            state.put("message", failureMessage == null || failureMessage.isBlank()
                ? "游戏资源安装失败。"
                : failureMessage);
            state.put("canPause", false);
            state.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException error) {
            return false;
        }
        writeStateAndBroadcast(context, state);
        return true;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        PAUSE_REQUESTED.set(true);
        super.onTimeout(startId, fgsType);
    }

    public static void clearAllDownloads(Context context) {
        deleteRecursively(getDownloadsRoot(context));
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(PREF_STATE)
            .remove(PREF_PLAN)
            .apply();
    }

    private static JSONObject idleState() {
        JSONObject state = new JSONObject();
        try {
            state.put("status", "idle");
            state.put("message", "等待下载");
            state.put("downloadedBytes", 0L);
            state.put("totalBytes", 0L);
            state.put("percent", 0.0);
            state.put("currentChunk", 0);
            state.put("totalChunks", 0);
            state.put("verifiedChunks", 0);
            state.put("canPause", false);
        } catch (JSONException ignored) {
        }
        return state;
    }

    private static JSONObject errorState(String message) {
        JSONObject state = idleState();
        try {
            state.put("status", "error");
            state.put("message", message);
        } catch (JSONException ignored) {
        }
        return state;
    }

    private long downloadedBytes() {
        if (activePlan == null) {
            return 0L;
        }
        File chunksDir = new File(new File(getDownloadsRoot(this), "work-" + activePlan.archiveSha256.substring(0, 12)), "chunks");
        return existingChunkBytes(activePlan, chunksDir);
    }

    private int currentChunkIndex() {
        return activePlan == null ? 0 : Math.min(activePlan.chunks.size(), verifiedChunks + 1);
    }

    private double currentPercent() {
        return activePlan == null || activePlan.totalBytes <= 0 ? 0.0 : Math.min(85.0, downloadedBytes() / (double) activePlan.totalBytes * 85.0);
    }

    private long existingChunkBytes(Plan plan, File chunksDir) {
        long total = 0L;
        for (Chunk chunk : plan.chunks) {
            File file = new File(chunksDir, chunk.fileName);
            total += Math.min(chunk.sizeBytes, Math.max(0L, file.length()));
        }
        return total;
    }

    private long requiredAvailableBytes(Plan plan, File archiveFile, long existingChunkBytes) {
        if (archiveFile.length() == plan.totalBytes) {
            return plan.totalBytes + 256L * 1024L * 1024L;
        }
        long required = DownloadFileUtils.requiredFreeBytes(plan.totalBytes);
        return Math.max(256L * 1024L * 1024L, required - existingChunkBytes);
    }

    private boolean hashMatches(File file, String expected) throws IOException {
        return DownloadFileUtils.sha256(file).equalsIgnoreCase(expected);
    }

    private void checkControlSignals() throws PausedException, CancelledException {
        if (CANCEL_REQUESTED.get()) {
            throw new CancelledException();
        }
        if (PAUSE_REQUESTED.get()) {
            throw new PausedException();
        }
    }

    private void sleepWithControl(long milliseconds) throws Exception {
        long remaining = milliseconds;
        while (remaining > 0) {
            checkControlSignals();
            long duration = Math.min(100L, remaining);
            Thread.sleep(duration);
            remaining -= duration;
        }
    }

    private static String readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String currentLauncherVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName == null || packageInfo.versionName.isBlank() ? "0.0.0" : packageInfo.versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private void prepareForPlan(File downloadsRoot, File currentWorkDir, Plan plan) throws IOException {
        JSONObject previous = readStateObject(this);
        boolean samePlan = plan.matchesState(previous);
        File[] children = downloadsRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().startsWith("work-") && !child.equals(currentWorkDir)) {
                    deleteRecursively(child);
                }
            }
        }
        if (!samePlan) {
            deleteRecursively(new File(downloadsRoot, "prepared"));
        }
        ensureDirectory(currentWorkDir);
    }

    private static File getDownloadsRoot(Context context) {
        File root = new File(context.getFilesDir(), "downloads");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null) {
            return;
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建目录：" + directory.getAbsolutePath());
        }
    }

    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("无法删除文件：" + file.getAbsolutePath());
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "游戏下载", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示零境交错游戏下载和校验进度");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(int percent, boolean ignoredCanPause) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("零境启动器")
            .setContentText("正在（下载）链接空界幻境中...")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, Math.max(0, Math.min(100, percent)), false)
            .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(int percent, boolean canPause) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(percent, canPause));
        } catch (SecurityException ignored) {
        }
    }

    private void showPausedNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("零境启动器")
            .setContentText("游戏下载已暂停")
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private void showCompletionNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("零境启动器")
            .setContentText("游戏下载完成，可以开始安装")
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private void showErrorNotification(String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("游戏下载失败")
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static final class PreparedFiles {
        final File apk;
        final File obb;
        final String installToken;

        PreparedFiles(File apk, File obb, String installToken) {
            this.apk = apk;
            this.obb = obb;
            this.installToken = installToken;
        }
    }

    private static final class Chunk {
        final int index;
        final int count;
        final String fileName;
        final String objectKey;
        final String sha256;
        final long sizeBytes;
        final String downloadUrl;

        Chunk(JSONObject source) throws JSONException {
            index = source.getInt("index");
            count = source.getInt("count");
            fileName = source.getString("fileName");
            objectKey = source.getString("objectKey");
            sha256 = source.getString("sha256");
            sizeBytes = source.getLong("sizeBytes");
            downloadUrl = source.optString("downloadUrl", "");
        }
    }

    private static final class Plan {
        final String productKey;
        final String runtime;
        final String source;
        final String version;
        final String archiveFileName;
        final String archiveSha256;
        final long totalBytes;
        final List<Chunk> chunks;

        private Plan(JSONObject source) throws JSONException {
            productKey = source.getString("productKey");
            runtime = source.getString("runtime");
            this.source = source.optString("source", "official");
            version = source.getString("version");
            archiveFileName = source.getString("archiveFileName");
            archiveSha256 = source.getString("archiveSha256").toLowerCase(Locale.ROOT);
            totalBytes = source.getLong("totalBytes");
            chunks = new ArrayList<>();
            JSONArray items = source.getJSONArray("chunks");
            for (int index = 0; index < items.length(); index++) {
                chunks.add(new Chunk(items.getJSONObject(index)));
            }
            chunks.sort(Comparator.comparingInt(chunk -> chunk.index));
            if ((!this.source.equals("official") && !this.source.equals("github")) || chunks.isEmpty() || totalBytes <= 0 || archiveSha256.length() != 64) {
                throw new JSONException("下载清单不完整");
            }
        }

        static Plan parse(String json) throws JSONException {
            return new Plan(new JSONObject(json));
        }

        boolean matchesState(JSONObject state) {
            return version.equals(state.optString("version")) && archiveSha256.equalsIgnoreCase(state.optString("archiveSha256"));
        }
    }

    private static final class PausedException extends Exception {
    }

    private static final class CancelledException extends Exception {
    }
}
