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

    static final String PREFS_NAME = "crossingvoid_launcher_update";
    static final String PREF_PLAN = "plan";
    static final String PREF_STATE = "state";
    static final String INSTALLER_PRODUCT_KEY = "crossingvoid-launcher-android-installer";
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);
    private static String lastLoggedStateSignature = "";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LauncherUpdateNotifier notifier;
    private LauncherUpdateDownloader downloader;
    private long lastStateAt;

    @Override
    public void onCreate() {
        super.onCreate();
        // ⚠️ 必须先挂上 notifier 再用它 —— 之前少了这一句，createChannel() 直接 NPE，
        // onCreate 抛异常 = 前台服务创建失败 = 进程闪退（用户 2026-09-22 报的"点更新启动器就崩"）。
        // 写法对齐 GameDownloadService.onCreate 里的 `notifier = DownloadNotifier.attach(this)`。
        notifier = LauncherUpdateNotifier.attach(this);
        notifier.createChannel();
        downloader = new LauncherUpdateDownloader(new LauncherUpdateDownloader.Host() {
            @Override
            public void checkCancelled() throws Exception {
                LauncherUpdateService.this.checkCancelled();
            }

            @Override
            public Context context() {
                return LauncherUpdateService.this;
            }

            @Override
            public void notifyProgress(int percent) {
                notifier.update(percent);
            }

            @Override
            public void notifyCompletion(String versionName) {
                notifier.showCompletion(versionName);
            }
        });
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
        notifier.startForeground(0);
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
                downloader.validateDownloadedApk(complete, plan);
                downloader.publishReady(plan, complete);
                return;
            }
            if (complete.exists()) complete.delete();
            if (partial.length() > plan.sizeBytes) partial.delete();

            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    downloader.download(plan, partial);
                    lastError = null;
                    break;
                } catch (CancelledException cancelled) {
                    throw cancelled;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt < MAX_ATTEMPTS) {
                        downloader.publishDownloading(plan, partial.length(), "连接中断，正在重试 " + (attempt + 1) + " / " + MAX_ATTEMPTS, true);
                        Thread.sleep(500L * attempt);
                    }
                }
            }
            if (lastError != null) throw lastError;
            checkCancelled();

            if (partial.length() != plan.sizeBytes) throw new IOException("启动器安装包大小不正确");
            downloader.publishDownloading(plan, partial.length(), "正在校验启动器安装包", true);
            if (!DownloadFileUtils.sha256(partial).equalsIgnoreCase(plan.sha256)) {
                partial.delete();
                throw new IOException("启动器安装包 SHA-256 校验失败");
            }
            if (!partial.renameTo(complete)) throw new IOException("无法保存启动器安装包");
            try {
                downloader.validateDownloadedApk(complete, plan);
            } catch (Exception error) {
                complete.delete();
                throw error;
            }
            downloader.publishReady(plan, complete);
            notifier.showCompletion(plan.versionName);
        } catch (CancelledException ignored) {
            clearAll(this);
            publishState(this, idleState());
            notifier.cancelActive();
        } catch (Exception error) {
            String message = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
            JSONObject state = errorState(message);
            if (plan != null) LauncherUpdateDownloader.putPlanIdentity(state, plan);
            publishState(this, state);
            notifier.showError(message);
        } finally {
            RUNNING.set(false);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(false);
            stopSelf();
        }
    }

    public static JSONObject clearIfInstalled(Context context, long currentVersionCode, String currentVersionName) {
        JSONObject state = readState(context);
        long targetVersionCode = state.optLong("versionCode", 0L);
        String targetVersionName = state.optString("versionName", "").trim();
        boolean newerCodeInstalled = currentVersionCode > targetVersionCode;
        boolean equalCodeInstalled = currentVersionCode == targetVersionCode &&
            (targetVersionName.isBlank() || LauncherUpdateVerifier.compareVersionNames(currentVersionName, targetVersionName) >= 0);
        if (targetVersionCode > 0 && (newerCodeInstalled || equalCodeInstalled)) {
            clearAll(context);
            state = idleState();
            publishState(context, state);
        }
        return state;
    }

    public static JSONObject readState(Context context) {
        return UpdateStateStore.readState(context);
    }

    private static JSONObject idleState() {
        return UpdateStateStore.idleState();
    }

    private static JSONObject errorState(String message) {
        return UpdateStateStore.errorState(message);
    }

    private static void publishState(Context context, JSONObject state) {
        UpdateStateStore.publishState(context, state);
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static void clearAll(Context context) {
        DownloadFileUtils.deleteRecursively(getUpdateRoot(context));
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
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
            if (!file.equals(partial) && !file.equals(complete)) DownloadFileUtils.deleteRecursively(file);
        }
    }

    private static final class CancelledException extends Exception {
    }
}
