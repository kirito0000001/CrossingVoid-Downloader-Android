package com.lingjing.launcher.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LauncherUpdateService extends Service {
    public static final String ACTION_START = "com.lingjing.launcher.android.action.START_LAUNCHER_UPDATE";
    public static final String ACTION_CANCEL = "com.lingjing.launcher.android.action.CANCEL_LAUNCHER_UPDATE";
    public static final String ACTION_STATE = "com.lingjing.launcher.android.action.LAUNCHER_UPDATE_STATE";
    public static final String EXTRA_PLAN = "launcherUpdatePlan";
    public static final String EXTRA_STATE = "launcherUpdateState";

    private static final String PREFS_NAME = "crossingvoid_launcher_update";
    private static final String PREF_PLAN = "plan";
    private static final String PREF_STATE = "state";
    private static final String INSTALLER_PRODUCT_KEY = "crossingvoid-launcher-android-installer";
    private static final String CHANNEL_ID = "crossingvoid_launcher_update";
    private static final int NOTIFICATION_ID = 2015;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final long STATE_INTERVAL_MS = 350L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);
    private static final Object STATE_LOCK = new Object();
    private static String lastLoggedStateSignature = "";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long lastStateAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            CANCEL_REQUESTED.set(true);
            if (!RUNNING.get()) {
                clearAll(this);
                publishState(this, idleState());
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        String planJson = intent == null ? "" : intent.getStringExtra(EXTRA_PLAN);
        if (planJson == null || planJson.isBlank()) {
            planJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PLAN, "");
        }
        if (planJson == null || planJson.isBlank()) {
            publishState(this, errorState("没有可恢复的启动器更新任务。"));
            stopSelf();
            return START_NOT_STICKY;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_PLAN, planJson).apply();
        CANCEL_REQUESTED.set(false);
        startForegroundCompat(buildNotification(0));
        if (RUNNING.compareAndSet(false, true)) {
            String finalPlanJson = planJson;
            executor.execute(() -> runUpdate(finalPlanJson));
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

    private void runUpdate(String planJson) {
        PowerManager.WakeLock wakeLock = null;
        UpdatePlan plan = null;
        try {
            plan = UpdatePlan.parse(planJson);
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossingVoidLauncher:SelfUpdate");
            wakeLock.acquire(30L * 60L * 1000L);

            File root = getUpdateRoot(this);
            File partial = new File(root, plan.fileName + ".part");
            File complete = new File(root, plan.fileName);
            deleteOtherVersions(root, partial, complete);

            if (complete.length() == plan.sizeBytes && DownloadFileUtils.sha256(complete).equalsIgnoreCase(plan.sha256)) {
                validateDownloadedApk(complete, plan);
                publishReady(plan, complete);
                return;
            }
            if (complete.exists()) complete.delete();
            if (partial.length() > plan.sizeBytes) partial.delete();

            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    download(plan, partial);
                    lastError = null;
                    break;
                } catch (CancelledException cancelled) {
                    throw cancelled;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt < MAX_ATTEMPTS) {
                        publishDownloading(plan, partial.length(), "连接中断，正在重试 " + (attempt + 1) + " / " + MAX_ATTEMPTS, true);
                        Thread.sleep(500L * attempt);
                    }
                }
            }
            if (lastError != null) throw lastError;
            checkCancelled();

            if (partial.length() != plan.sizeBytes) throw new IOException("启动器安装包大小不正确");
            publishDownloading(plan, partial.length(), "正在校验启动器安装包", true);
            if (!DownloadFileUtils.sha256(partial).equalsIgnoreCase(plan.sha256)) {
                partial.delete();
                throw new IOException("启动器安装包 SHA-256 校验失败");
            }
            if (!partial.renameTo(complete)) throw new IOException("无法保存启动器安装包");
            try {
                validateDownloadedApk(complete, plan);
            } catch (Exception error) {
                complete.delete();
                throw error;
            }
            publishReady(plan, complete);
            showCompletionNotification(plan.versionName);
        } catch (CancelledException ignored) {
            clearAll(this);
            publishState(this, idleState());
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        } catch (Exception error) {
            String message = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
            JSONObject state = errorState(message);
            if (plan != null) putPlanIdentity(state, plan);
            publishState(this, state);
            showErrorNotification(message);
        } finally {
            RUNNING.set(false);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(false);
            stopSelf();
        }
    }

    private void download(UpdatePlan plan, File outputFile) throws Exception {
        long resumeFrom = outputFile.exists() ? outputFile.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection) new URL(plan.url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "CrossingVoidAndroidLauncher/" + plan.versionName);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream");
        if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("下载服务器返回 HTTP " + status);
            }
            if (resumeFrom > 0 && status != HttpURLConnection.HTTP_PARTIAL) resumeFrom = 0L;
            try (
                InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                RandomAccessFile output = new RandomAccessFile(outputFile, "rw")
            ) {
                if (resumeFrom == 0L) output.setLength(0L);
                output.seek(resumeFrom);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkCancelled();
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    publishDownloading(plan, output.length(), "正在下载启动器 " + plan.versionName, false);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void publishDownloading(UpdatePlan plan, long downloaded, String message, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastStateAt < STATE_INTERVAL_MS) return;
        lastStateAt = now;
        JSONObject state = new JSONObject();
        try {
            state.put("status", "downloading");
            state.put("message", message);
            state.put("downloadedBytes", Math.max(0L, downloaded));
            state.put("totalBytes", plan.sizeBytes);
            state.put("percent", Math.min(100.0, downloaded / (double) plan.sizeBytes * 100.0));
            putPlanIdentity(state, plan);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        publishState(this, state);
        updateNotification((int) Math.round(state.optDouble("percent", 0.0)));
    }

    private void validateDownloadedApk(File apk, UpdatePlan plan) throws Exception {
        PackageManager packageManager = getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !getPackageName().equals(candidate.packageName)) {
            throw new IOException("启动器安装包包名不正确");
        }
        if (packageVersionCode(candidate) != plan.versionCode) {
            throw new IOException("启动器安装包 versionCode 不正确");
        }

        PackageInfo installed = packageManager.getPackageInfo(getPackageName(), flags);
        Signature[] installedSignatures = packageSignatures(installed);
        Signature[] candidateSignatures = packageSignatures(candidate);
        if (installedSignatures.length == 0 || candidateSignatures.length == 0 ||
            !sameSignatures(installedSignatures, candidateSignatures)) {
            throw new IOException("启动器安装包签名不一致");
        }
    }

    private static long packageVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }

    private static Signature[] packageSignatures(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (packageInfo.signingInfo == null) return new Signature[0];
            return packageInfo.signingInfo.hasMultipleSigners()
                ? packageInfo.signingInfo.getApkContentsSigners()
                : packageInfo.signingInfo.getSigningCertificateHistory();
        }
        return packageInfo.signatures == null ? new Signature[0] : packageInfo.signatures;
    }

    private static boolean sameSignatures(Signature[] left, Signature[] right) {
        if (left.length != right.length) return false;
        String[] leftValues = new String[left.length];
        String[] rightValues = new String[right.length];
        for (int index = 0; index < left.length; index++) leftValues[index] = left[index].toCharsString();
        for (int index = 0; index < right.length; index++) rightValues[index] = right[index].toCharsString();
        Arrays.sort(leftValues);
        Arrays.sort(rightValues);
        return Arrays.equals(leftValues, rightValues);
    }

    private void publishReady(UpdatePlan plan, File apk) {
        JSONObject state = new JSONObject();
        try {
            state.put("status", "ready");
            state.put("message", "启动器更新已准备完成");
            state.put("downloadedBytes", plan.sizeBytes);
            state.put("totalBytes", plan.sizeBytes);
            state.put("percent", 100.0);
            state.put("apkPath", apk.getAbsolutePath());
            putPlanIdentity(state, plan);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        publishState(this, state);
    }

    private static void putPlanIdentity(JSONObject state, UpdatePlan plan) {
        try {
            state.put("versionName", plan.versionName);
            state.put("versionCode", plan.versionCode);
            state.put("sha256", plan.sha256);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    public static JSONObject readState(Context context) {
        synchronized (STATE_LOCK) {
            String json = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_STATE, "");
            if (json == null || json.isBlank()) return idleState();
            try {
                return new JSONObject(json);
            } catch (JSONException ignored) {
                return errorState("启动器更新状态已损坏，请重新下载。");
            }
        }
    }

    public static JSONObject clearIfInstalled(Context context, long currentVersionCode, String currentVersionName) {
        JSONObject state = readState(context);
        long targetVersionCode = state.optLong("versionCode", 0L);
        String targetVersionName = state.optString("versionName", "").trim();
        boolean newerCodeInstalled = currentVersionCode > targetVersionCode;
        boolean equalCodeInstalled = currentVersionCode == targetVersionCode &&
            (targetVersionName.isBlank() || compareVersionNames(currentVersionName, targetVersionName) >= 0);
        if (targetVersionCode > 0 && (newerCodeInstalled || equalCodeInstalled)) {
            clearAll(context);
            state = idleState();
            publishState(context, state);
        }
        return state;
    }

    private static int compareVersionNames(String left, String right) {
        String[] leftParts = (left == null ? "" : left).split("\\.");
        String[] rightParts = (right == null ? "" : right).split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void clearAll(Context context) {
        deleteRecursively(getUpdateRoot(context));
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
    }

    private static void publishState(Context context, JSONObject state) {
        String json = state.toString();
        String status = state.optString("status", "idle");
        String message = state.optString("message", status);
        String logSignature = status + "|" + message;
        boolean shouldLog;
        synchronized (STATE_LOCK) {
            shouldLog = !logSignature.equals(lastLoggedStateSignature);
            if (shouldLog) lastLoggedStateSignature = logSignature;
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_STATE, json).apply();
        }
        if (shouldLog) {
            String level = status.equals("error") ? "error" : "info";
            try {
                LauncherLogStore.append(context, level, "launcher-update.state", message, json);
            } catch (Exception ignored) {
            }
        }
        Intent update = new Intent(ACTION_STATE).setPackage(context.getPackageName());
        update.putExtra(EXTRA_STATE, json);
        context.sendBroadcast(update);
    }

    private static JSONObject idleState() {
        JSONObject state = new JSONObject();
        try {
            state.put("status", "idle");
            state.put("message", "启动器已是最新版本");
            state.put("downloadedBytes", 0L);
            state.put("totalBytes", 0L);
            state.put("percent", 0.0);
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

    private void checkCancelled() throws CancelledException {
        if (CANCEL_REQUESTED.get()) throw new CancelledException();
    }

    private static File getUpdateRoot(Context context) {
        File root = new File(context.getFilesDir(), "launcher-update");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private static void deleteOtherVersions(File root, File partial, File complete) {
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(partial) && !file.equals(complete)) deleteRecursively(file);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "启动器更新", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示零境启动器更新进度");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(int percent) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("零境启动器")
            .setContentText("正在更新零境启动器...")
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

    private void updateNotification(int percent) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(percent));
        } catch (SecurityException ignored) {
        }
    }

    private void showCompletionNotification(String versionName) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("启动器更新已就绪")
            .setContentText("版本 " + versionName + " 可以安装")
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private void showErrorNotification(String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("启动器更新失败")
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
    }

    private static final class UpdatePlan {
        final String versionName;
        final long versionCode;
        final String fileName;
        final String url;
        final long sizeBytes;
        final String sha256;

        UpdatePlan(JSONObject source) throws JSONException {
            String productKey = source.getString("productKey");
            if (!INSTALLER_PRODUCT_KEY.equals(productKey)) {
                throw new JSONException("启动器更新产品标识不正确");
            }
            versionName = source.getString("versionName");
            versionCode = source.getLong("versionCode");
            JSONObject asset = source.getJSONObject("asset");
            fileName = asset.getString("fileName");
            url = asset.getString("url");
            sizeBytes = asset.getLong("sizeBytes");
            sha256 = asset.getString("sha256").toLowerCase();
            if (versionCode <= 0 || sizeBytes <= 0 || sha256.length() != 64 || !url.startsWith("https://")) {
                throw new JSONException("启动器更新清单不完整");
            }
        }

        static UpdatePlan parse(String json) throws JSONException {
            return new UpdatePlan(new JSONObject(json));
        }
    }

    private static final class CancelledException extends Exception {
    }
}
