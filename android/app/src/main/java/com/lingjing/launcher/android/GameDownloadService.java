package com.lingjing.launcher.android;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GameDownloadService extends Service {
    public static final String ACTION_START = "com.lingjing.launcher.android.action.START_DOWNLOAD";
    /** 清单 v1：按文件下载 + 组装 OBB（与老的切片流程并存，插件按计划形状路由）。 */
    public static final String ACTION_START_PACKAGE = "com.lingjing.launcher.android.action.START_PACKAGE_DOWNLOAD";
    public static final String ACTION_PAUSE = "com.lingjing.launcher.android.action.PAUSE_DOWNLOAD";
    public static final String ACTION_CANCEL = "com.lingjing.launcher.android.action.CANCEL_DOWNLOAD";
    public static final String ACTION_IMPORT = "com.lingjing.launcher.android.action.IMPORT_CHUNKS";
    public static final String ACTION_EXPORT = "com.lingjing.launcher.android.action.EXPORT_CHUNKS";
    public static final String ACTION_STATE = "com.lingjing.launcher.android.action.DOWNLOAD_STATE";
    public static final String EXTRA_PLAN = "downloadPlan";
    public static final String EXTRA_STATE = "downloadState";
    public static final String EXTRA_IMPORT_TREE_URI = "importTreeUri";
    public static final String EXTRA_EXPORT_TREE_URI = "exportTreeUri";

    private static final int MAX_ATTEMPTS = 3;
    private static final long STATE_INTERVAL_MS = 350L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean PAUSE_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long lastStateAt;
    private long lastRateBytes;
    private long lastRateAt;
    private double bytesPerSecond;
    private DownloadPlan activePlan;
    private GamePackagePlan activePackagePlan;
    private int verifiedChunks;
    private int verifiedFiles;
    private String lastLoggedStateSignature = "";
    private DownloadNotifier notifier;
    private ChunkTransfer transfer;
    private PackageInstaller installer;
    private ChunkDownloader downloader;
    private PackageFileDownloader packageDownloader;

    @Override
    public void onCreate() {
        super.onCreate();
        notifier = DownloadNotifier.attach(this);
        notifier.createChannel();
        transfer = new ChunkTransfer(getContentResolver(), new ChunkTransfer.Host() {
            @Override
            public void checkControlSignals() throws Exception {
                GameDownloadService.this.checkControlSignals();
            }

            @Override
            public void publishState(String status, String message, long downloadedBytes, double percent, int currentChunk, boolean force, PreparedFiles prepared) {
                GameDownloadService.this.publishState(status, message, downloadedBytes, percent, currentChunk, force, prepared);
            }

            @Override
            public void saveState(JSONObject state) {
                saveAndBroadcastState(state);
            }

            @Override
            public void notifyResult(boolean success, String message) {
                if (success) notifier.showCompletion();
                else notifier.showError(message);
            }

            @Override
            public void cleanStaleObb(File obbDir, File currentObb) {
                installer.removeStaleObbFiles(obbDir, currentObb);
            }
        });
        downloader = new ChunkDownloader(new ChunkDownloader.Host() {
            @Override
            public void publishProgress(String message, boolean force) {
                publishDownloadProgress(message, force);
            }

            @Override
            public void checkControlSignals() throws Exception {
                GameDownloadService.this.checkControlSignals();
            }
        });
        packageDownloader = new PackageFileDownloader(new PackageFileDownloader.Host() {
            @Override
            public void publishProgress(String message, boolean force) {
                publishPackageProgress(message, force);
            }

            @Override
            public void checkControlSignals() throws Exception {
                GameDownloadService.this.checkControlSignals();
            }
        }, "CrossingVoidAndroidLauncher/" + currentLauncherVersion());
        installer = new PackageInstaller(new PackageInstaller.Progress() {
            @Override
            public void publish(String status, String message, long downloadedBytes, double percent, int currentChunk, boolean force) {
                publishState(status, message, downloadedBytes, percent, currentChunk, force, null);
            }

            @Override
            public void checkControlSignals() throws Exception {
                GameDownloadService.this.checkControlSignals();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            PAUSE_REQUESTED.set(true);
            publishTransitionState("pausing", "正在暂停下载");
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            CANCEL_REQUESTED.set(true);
            PAUSE_REQUESTED.set(false);
            publishTransitionState("cancelling", "正在取消并清理下载文件");
            if (!RUNNING.get()) {
                clearAllDownloads(this);
                saveAndBroadcastState(idleState());
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_START_PACKAGE.equals(action)) {
            String packagePlanJson = intent == null ? null : intent.getStringExtra(EXTRA_PLAN);
            if (packagePlanJson == null || packagePlanJson.isBlank()) {
                saveAndBroadcastState(errorState("缺少游戏下载清单。"));
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!RUNNING.compareAndSet(false, true)) {
                return START_REDELIVER_INTENT;
            }
            PAUSE_REQUESTED.set(false);
            CANCEL_REQUESTED.set(false);
            LauncherStorage.prefs(this).edit().putString(LauncherStorage.PREF_PLAN, packagePlanJson).apply();
            notifier.startForeground(0);
            String finalPackagePlanJson = packagePlanJson;
            executor.execute(() -> runPackageDownload(finalPackagePlanJson, startId));
            return START_REDELIVER_INTENT;
        }

        if (ACTION_EXPORT.equals(action)) {
            String exportTreeUri = intent == null ? null : intent.getStringExtra(EXTRA_EXPORT_TREE_URI);
            String exportPlan = LauncherStorage.prefs(this).getString(LauncherStorage.PREF_PLAN, "");
            JSONObject previousState = readStateObject(this);
            String previousStatus = previousState.optString("status");
            boolean exportableState = "ready".equals(previousStatus)
                || ("error".equals(previousState.optString("status"))
                    && !previousState.optString("apkPath").isBlank()
                    && !previousState.optString("obbPath").isBlank());
            if (exportTreeUri == null || exportTreeUri.isBlank() || exportPlan == null || exportPlan.isBlank()
                || !exportableState) {
                saveAndBroadcastState(errorState("当前没有可以导出的已下载游戏文件。"));
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!RUNNING.compareAndSet(false, true)) {
                saveAndBroadcastState(errorState("上一项游戏下载任务正在结束，请稍后再导出。"));
                return START_NOT_STICKY;
            }
            PAUSE_REQUESTED.set(false);
            CANCEL_REQUESTED.set(false);
            verifiedChunks = previousState.optInt("verifiedChunks", 0);
            notifier.startForeground(0);
            executor.execute(() -> runExport(exportPlan, Uri.parse(exportTreeUri), previousState, startId));
            return START_NOT_STICKY;
        }

        if (ACTION_IMPORT.equals(action)) {
            String importPlan = intent == null ? null : intent.getStringExtra(EXTRA_PLAN);
            String importTreeUri = intent == null ? null : intent.getStringExtra(EXTRA_IMPORT_TREE_URI);
            if (importPlan == null || importPlan.isBlank() || importTreeUri == null || importTreeUri.isBlank()) {
                saveAndBroadcastState(errorState("缺少要导入的游戏碎片或下载清单。"));
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!RUNNING.compareAndSet(false, true)) {
                saveAndBroadcastState(errorState("上一项游戏下载任务正在结束，请稍后再导入碎片。"));
                return START_NOT_STICKY;
            }
            PAUSE_REQUESTED.set(false);
            CANCEL_REQUESTED.set(false);
            LauncherStorage.prefs(this).edit().putString(LauncherStorage.PREF_PLAN, importPlan).apply();
            notifier.startForeground(0);
            // 逐文件清单（v1）走 package 导入：碎片是散件 + 压缩包，按清单路径对，
            // 落点和下载那条链路完全一样（packageFileTarget + preparePackageArtifacts），
            // 所以组装 OBB 那段不用另写一份。
            // 老的分片清单（chunks[]）留着兼容，但线上已经不发那种包了。
            boolean packagePlan = GameDownloadService.looksLikePackagePlan(importPlan);
            executor.execute(() -> packagePlan
                ? runPackageImport(importPlan, Uri.parse(importTreeUri), startId)
                : runImport(importPlan, Uri.parse(importTreeUri), startId));
            return START_NOT_STICKY;
        }

        String planJson = intent == null ? null : intent.getStringExtra(EXTRA_PLAN);
        if (planJson == null || planJson.isBlank()) {
            planJson = LauncherStorage.prefs(this).getString(LauncherStorage.PREF_PLAN, "");
        }
        if (planJson == null || planJson.isBlank()) {
            saveAndBroadcastState(errorState("没有可恢复的下载任务。"));
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            return START_REDELIVER_INTENT;
        }
        PAUSE_REQUESTED.set(false);
        CANCEL_REQUESTED.set(false);
        LauncherStorage.prefs(this).edit().putString(LauncherStorage.PREF_PLAN, planJson).apply();
        notifier.startForeground(0);
        String finalPlanJson = planJson;
        executor.execute(() -> runDownload(finalPlanJson, startId));
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

    private void runDownload(String planJson, int startId) {
        PowerManager.WakeLock wakeLock = null;
        String terminalStatus = null;
        String terminalMessage = null;
        PreparedFiles terminalPrepared = null;
        boolean cancelled = false;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossingVoidLauncher:GameDownload");
            wakeLock.acquire(6L * 60L * 60L * 1000L);

            activePlan = DownloadPlan.parse(planJson);
            File downloadsRoot = LauncherStorage.downloadsRoot(this);
            File workDir = new File(downloadsRoot, "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            File archiveFile = new File(workDir, activePlan.archiveFileName);
            DownloadFileUtils.ensureDirectory(chunksDir);
            prepareForPlan(downloadsRoot, workDir, activePlan);

            JSONObject previous = readStateObject(this);
            verifiedChunks = activePlan.matchesState(previous) ? previous.optInt("verifiedChunks", 0) : 0;
            verifiedChunks = Math.max(0, Math.min(verifiedChunks, activePlan.chunks.size()));
            long existingBytes = DownloadFileUtils.existingChunkBytes(activePlan, chunksDir);
            long requiredBytes = requiredAvailableBytes(activePlan, archiveFile, existingBytes);
            long availableBytes = new StatFs(downloadsRoot.getAbsolutePath()).getAvailableBytes();
            if (availableBytes < requiredBytes) {
                throw new IOException("存储空间不足：还需要 " + formatBytes(requiredBytes) + "，当前可用 " + formatBytes(availableBytes));
            }

            if (archiveFile.length() != activePlan.totalBytes) {
                downloadChunks(activePlan, chunksDir);
                checkControlSignals();
                installer.mergeChunks(activePlan, chunksDir, archiveFile);
            }
            checkControlSignals();
            installer.verifyArchive(activePlan, archiveFile);
            checkControlSignals();
            terminalPrepared = installer.extractPackage(activePlan, archiveFile, downloadsRoot, getObbDir());
            DownloadFileUtils.deleteRecursively(workDir);
            terminalStatus = "ready";
            terminalMessage = "APK 和 OBB 已准备完成";
        } catch (PausedException ignored) {
            terminalStatus = "paused";
            terminalMessage = "下载已暂停，稍后可以继续";
        } catch (CancelledException ignored) {
            cancelled = true;
        } catch (Exception error) {
            terminalStatus = "error";
            terminalMessage = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            finishWorker(terminalStatus, terminalMessage, terminalPrepared, cancelled);
            RUNNING.set(false);
            stopForeground(false);
            stopSelfResult(startId);
        }
    }

    private void runImport(String planJson, Uri treeUri, int startId) {
        String terminalStatus = null;
        String terminalMessage = null;
        PreparedFiles terminalPrepared = null;
        boolean cancelled = false;
        try {
            activePlan = DownloadPlan.parse(planJson);
            File downloadsRoot = LauncherStorage.downloadsRoot(this);
            File workDir = new File(downloadsRoot, "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            DownloadFileUtils.ensureDirectory(chunksDir);
            prepareForPlan(downloadsRoot, workDir, activePlan);
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.isDirectory()) throw new IOException("无法读取选择的游戏碎片文件夹。");
            List<DocumentFile> sourceFiles = new ArrayList<>();
            transfer.collectChunkDocuments(activePlan, root, sourceFiles);
            if (sourceFiles.isEmpty()) {
                terminalPrepared = transfer.importPreparedRecoveryFiles(root, activePlan, downloadsRoot, getObbDir());
                terminalStatus = "ready";
                terminalMessage = "恢复文件校验完成，APK 和 OBB 已准备完成";
                verifiedChunks = activePlan.chunks.size();
                return;
            }
            int imported = 0;
            for (DocumentFile sourceFile : sourceFiles) {
                checkControlSignals();
                String name = sourceFile.getName();
                DownloadChunk chunk = transfer.findChunkByName(activePlan, name);
                if (chunk == null) continue;
                File destination = new File(chunksDir, chunk.fileName);
                if (destination.length() == chunk.sizeBytes && DownloadFileUtils.hashMatches(destination, chunk.sha256)) {
                    continue;
                }
                DownloadFileUtils.deleteFile(destination);
                transfer.copyAndVerifyImportedChunk(sourceFile, chunk, destination, chunksDir, activePlan);
                imported++;
            }
            verifiedChunks = verifiedChunkCount(activePlan, chunksDir);
            if (verifiedChunks == activePlan.chunks.size()) {
                File archiveFile = new File(workDir, activePlan.archiveFileName);
                installer.mergeChunks(activePlan, chunksDir, archiveFile);
                installer.verifyArchive(activePlan, archiveFile);
                terminalPrepared = installer.extractPackage(activePlan, archiveFile, downloadsRoot, getObbDir());
                DownloadFileUtils.deleteRecursively(workDir);
                terminalStatus = "ready";
                terminalMessage = "游戏碎片校验完成，APK 和 OBB 已准备完成";
            } else {
                terminalStatus = "paused";
                terminalMessage = "已导入 " + imported + " 个碎片，已准备 " + verifiedChunks + " / " + activePlan.chunks.size() + " 片";
            }
        } catch (PausedException ignored) {
            terminalStatus = "paused";
            terminalMessage = "导入已暂停，已校验的碎片会保留";
        } catch (CancelledException ignored) {
            cancelled = true;
        } catch (Exception error) {
            terminalStatus = "error";
            terminalMessage = error.getMessage() == null || error.getMessage().isBlank() ? "导入游戏碎片失败" : error.getMessage();
        } finally {
            finishWorker(terminalStatus, terminalMessage, terminalPrepared, cancelled);
            RUNNING.set(false);
            stopForeground(false);
            stopSelfResult(startId);
        }
    }

    private void runExport(String planJson, Uri treeUri, JSONObject previousState, int startId) {
        boolean success = false;
        String message = "已下载游戏文件导出完成";
        try {
            activePlan = DownloadPlan.parse(planJson);
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.isDirectory() || !root.canWrite()) {
                throw new IOException("无法写入选择的导出文件夹。");
            }
            File workDir = new File(LauncherStorage.downloadsRoot(this), "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            if (transfer.allChunksAvailable(activePlan, chunksDir)) {
                transfer.exportVerifiedChunks(root, activePlan, chunksDir);
                message = "游戏碎片已导出，可以卸载旧启动器";
            } else {
                transfer.exportPreparedRecoveryFiles(activePlan, root, previousState);
                message = "游戏恢复文件已导出，可以卸载旧启动器";
            }
            success = true;
        } catch (Exception error) {
            message = "导出失败：" + (error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage());
            try {
                LauncherLogStore.append(this, "error", "game-download.export", message, error.toString());
            } catch (Exception ignored) {
            }
        } finally {
            transfer.restoreStateAfterExport(previousState, success, message);
            RUNNING.set(false);
            stopForeground(false);
            stopSelfResult(startId);
        }
    }

    private void finishWorker(String status, String message, PreparedFiles prepared, boolean cancelled) {
        PAUSE_REQUESTED.set(false);
        CANCEL_REQUESTED.set(false);
        if (cancelled) {
            clearAllDownloads(this);
            saveAndBroadcastState(idleState());
            notifier.cancelActive();
            return;
        }
        String terminalStatus = status == null ? "error" : status;
        String terminalMessage = message == null || message.isBlank() ? "游戏下载任务意外结束" : message;
        if (terminalStatus.equals("idle")) {
            saveAndBroadcastState(idleState());
            return;
        }
        long bytes = terminalStatus.equals("ready") && activePlan != null ? activePlan.totalBytes : downloadedBytes();
        double percent = terminalStatus.equals("ready") ? 100.0 : currentPercent();
        int chunk = terminalStatus.equals("ready") && activePlan != null ? activePlan.chunks.size() : currentChunkIndex();
        publishState(terminalStatus, terminalMessage, bytes, percent, chunk, true, prepared);
        if (terminalStatus.equals("ready")) notifier.showCompletion();
        else if (terminalStatus.equals("paused")) notifier.showPaused();
        else if (terminalStatus.equals("error")) notifier.showError(terminalMessage);
    }

    private void publishTransitionState(String status, String message) {
        if (!RUNNING.get()) return;
        JSONObject state = readStateObject(this);
        try {
            state.put("status", status);
            state.put("message", message);
            state.put("canPause", false);
            state.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException error) {
            return;
        }
        saveAndBroadcastState(state);
    }

    private int verifiedChunkCount(DownloadPlan plan, File chunksDir) throws IOException {
        int count = 0;
        for (DownloadChunk chunk : plan.chunks) {
            File file = new File(chunksDir, chunk.fileName);
            if (file.length() == chunk.sizeBytes && DownloadFileUtils.hashMatches(file, chunk.sha256)) count++;
        }
        return count;
    }

    private void downloadChunks(DownloadPlan plan, File chunksDir) throws Exception {
        for (int position = 0; position < plan.chunks.size(); position++) {
            checkControlSignals();
            DownloadChunk chunk = plan.chunks.get(position);
            File file = new File(chunksDir, chunk.fileName);

            if (position < verifiedChunks && file.length() == chunk.sizeBytes) {
                continue;
            }
            if (file.length() == chunk.sizeBytes && DownloadFileUtils.hashMatches(file, chunk.sha256)) {
                verifiedChunks = position + 1;
                publishDownloadProgress("已校验第 " + chunk.index + " / " + chunk.count + " 片", true);
                continue;
            }
            if (file.length() > chunk.sizeBytes || file.length() == chunk.sizeBytes) {
                DownloadFileUtils.deleteFile(file);
            }

            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                checkControlSignals();
                try {
                    downloader.download(plan, chunk, file, currentLauncherVersion());
                    if (file.length() != chunk.sizeBytes) {
                        throw new IOException("第 " + chunk.index + " 片大小不正确");
                    }
                    publishState("verifying", "正在校验第 " + chunk.index + " / " + chunk.count + " 片", downloadedBytes(), currentPercent(), chunk.index, true, null);
                    if (!DownloadFileUtils.hashMatches(file, chunk.sha256)) {
                        DownloadFileUtils.deleteFile(file);
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
            } else if (status.equals("exporting")) {
                copyPreparedStateFields(state, readStateObject(this));
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
        if (status.equals("downloading") || status.equals("exporting") || status.equals("verifying") || status.equals("merging") || status.equals("extracting")) {
            notifier.update((int) Math.round(percent));
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

    public static JSONObject readStateObject(Context context) {
        return DownloadStateStore.readStateObject(context);
    }
    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String getManagedVersion(Context context) {
        return DownloadStateStore.getManagedVersion(context);
    }
    public static void clearManagedVersion(Context context) {
        DownloadStateStore.clearManagedVersion(context);
    }
    public static boolean completeInstallation(Context context, String installToken) {
        return DownloadStateStore.completeInstallation(context, installToken);
    }
    public static boolean failInstallation(Context context, String installToken, String failureMessage) {
        return DownloadStateStore.failInstallation(context, installToken, failureMessage);
    }
    @Override
    public void onTimeout(int startId, int fgsType) {
        PAUSE_REQUESTED.set(true);
        super.onTimeout(startId, fgsType);
    }

    private static JSONObject idleState() {
        return DownloadStateStore.idleState();
    }

    private static JSONObject errorState(String message) {
        return DownloadStateStore.errorState(message);
    }

    private static void writeStateAndBroadcast(Context context, JSONObject state) {
        DownloadStateStore.writeStateAndBroadcast(context, state);
    }

    private static void copyPreparedStateFields(JSONObject target, JSONObject source) throws JSONException {
        DownloadStateStore.copyPreparedStateFields(target, source);
    }

    public static void clearAllDownloads(Context context) {
        DownloadStateStore.clearAllDownloads(context);
    }

    private long downloadedBytes() {
        if (activePlan == null) {
            return 0L;
        }
        File chunksDir = new File(new File(LauncherStorage.downloadsRoot(this), "work-" + activePlan.archiveSha256.substring(0, 12)), "chunks");
        return DownloadFileUtils.existingChunkBytes(activePlan, chunksDir);
    }

    private int currentChunkIndex() {
        return activePlan == null ? 0 : Math.min(activePlan.chunks.size(), verifiedChunks + 1);
    }

    private double currentPercent() {
        return activePlan == null || activePlan.totalBytes <= 0 ? 0.0 : Math.min(85.0, downloadedBytes() / (double) activePlan.totalBytes * 85.0);
    }

    private long requiredAvailableBytes(DownloadPlan plan, File archiveFile, long existingChunkBytes) {
        if (archiveFile.length() == plan.totalBytes) {
            return plan.totalBytes + 256L * 1024L * 1024L;
        }
        long required = DownloadFileUtils.requiredFreeBytes(plan.totalBytes);
        return Math.max(256L * 1024L * 1024L, required - existingChunkBytes);
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

    private String currentLauncherVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName == null || packageInfo.versionName.isBlank() ? "0.0.0" : packageInfo.versionName;
        } catch (Exception ignored) {
            return "0.0.0";
        }
    }

    private void prepareForPlan(File downloadsRoot, File currentWorkDir, DownloadPlan plan) throws IOException {
        JSONObject previous = readStateObject(this);
        boolean samePlan = plan.matchesState(previous);
        File[] children = downloadsRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().startsWith("work-") && !child.equals(currentWorkDir)) {
                    DownloadFileUtils.deleteRecursively(child);
                }
            }
        }
        if (!samePlan) {
            DownloadFileUtils.deleteRecursively(new File(downloadsRoot, "prepared"));
        }
        DownloadFileUtils.ensureDirectory(currentWorkDir);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------
    // 清单 v1：按文件下载 → 组装 OBB → 准备 APK
    // ------------------------------------------------------------------

    private void runPackageDownload(String planJson, int startId) {
        PowerManager.WakeLock wakeLock = null;
        String terminalStatus = null;
        String terminalMessage = null;
        PreparedFiles terminalPrepared = null;
        boolean cancelled = false;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossingVoidLauncher:GamePackage");
            wakeLock.acquire(6L * 60L * 60L * 1000L);

            activePackagePlan = GamePackagePlan.parse(planJson);
            LauncherStorage.prefs(this).edit()
                .putString(LauncherStorage.PREF_PLAN, planJson).apply();

            File downloadsRoot = LauncherStorage.downloadsRoot(this);
            File workDir = new File(downloadsRoot, "package-" + activePackagePlan.version);
            File entriesDir = new File(workDir, "entries");
            File preparedDir = new File(downloadsRoot, "prepared");
            File apkTarget = new File(preparedDir, "CrossingVoid-latest.apk");
            File obbDir = getObbDir();
            File obbTarget = activePackagePlan.obbFileName.isEmpty() || obbDir == null
                ? null
                : new File(obbDir, activePackagePlan.obbFileName);

            preparePackageWork(downloadsRoot, workDir, preparedDir);
            JSONObject previous = readStateObject(this);
            verifiedFiles = activePackagePlan.matchesState(previous) ? previous.optInt("verifiedFiles", 0) : 0;
            verifiedFiles = Math.max(0, Math.min(verifiedFiles, activePackagePlan.files.size()));

            long requiredBytes = missingPackageBytes(entriesDir, apkTarget, preparedDir) + packageObbBytes();
            requiredBytes = Math.max(256L * 1024L * 1024L, requiredBytes);
            long availableBytes = new StatFs(downloadsRoot.getAbsolutePath()).getAvailableBytes();
            if (availableBytes < requiredBytes) {
                throw new IOException("存储空间不足：还需要 " + formatBytes(requiredBytes)
                    + "，当前可用 " + formatBytes(availableBytes));
            }

            downloadPackageFiles(entriesDir, apkTarget, preparedDir);
            checkControlSignals();
            publishPackageState("extracting", "正在组装 OBB 并准备 APK", downloadedBytesForPackage(),
                90.0, activePackagePlan.files.size(), true, null);
            terminalPrepared = preparePackageArtifacts(entriesDir, apkTarget, obbTarget);
            DownloadFileUtils.deleteRecursively(workDir);
            terminalStatus = "ready";
            terminalMessage = "APK 和 OBB 已准备完成";
        } catch (PausedException ignored) {
            terminalStatus = "paused";
            terminalMessage = "下载已暂停，稍后可以继续";
        } catch (CancelledException ignored) {
            cancelled = true;
        } catch (Exception error) {
            terminalStatus = "error";
            terminalMessage = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            finishPackageWorker(terminalStatus, terminalMessage, terminalPrepared, cancelled);
            RUNNING.set(false);
            stopForeground(false);
            stopSelfResult(startId);
        }
    }

    /** 这个清单是不是逐文件（v1）的：有 `files[]` 就是。 */
    private static boolean looksLikePackagePlan(String planJson) {
        try {
            return new JSONObject(planJson).has("files");
        } catch (JSONException error) {
            return false;
        }
    }

    /**
     * 导入碎片：玩家自己从网盘 / QQ 群拿到的那批文件。
     *
     * 落点和下载那条链路**完全一致** —— 先按清单把文件对到 {@code packageFileTarget} 说的位置，
     * 全齐了再走同一个 {@code preparePackageArtifacts} 组装 OBB。所以这里没有第二套"怎么装"的逻辑。
     * 已经对上的文件不会重拷，导到一半中断、再点一次接着来。
     */
    private void runPackageImport(String planJson, Uri treeUri, int startId) {
        PowerManager.WakeLock wakeLock = null;
        String terminalStatus = null;
        String terminalMessage = null;
        PreparedFiles terminalPrepared = null;
        boolean cancelled = false;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossingVoidLauncher:GameImport");
            wakeLock.acquire(6L * 60L * 60L * 1000L);

            activePackagePlan = GamePackagePlan.parse(planJson);
            LauncherStorage.prefs(this).edit()
                .putString(LauncherStorage.PREF_PLAN, planJson).apply();

            File downloadsRoot = LauncherStorage.downloadsRoot(this);
            File workDir = new File(downloadsRoot, "package-" + activePackagePlan.version);
            File entriesDir = new File(workDir, "entries");
            File preparedDir = new File(downloadsRoot, "prepared");
            File apkTarget = new File(preparedDir, "CrossingVoid-latest.apk");
            File obbDir = getObbDir();
            File obbTarget = activePackagePlan.obbFileName.isEmpty() || obbDir == null
                ? null
                : new File(obbDir, activePackagePlan.obbFileName);

            preparePackageWork(downloadsRoot, workDir, preparedDir);
            JSONObject previous = readStateObject(this);
            verifiedFiles = activePackagePlan.matchesState(previous) ? previous.optInt("verifiedFiles", 0) : 0;
            verifiedFiles = Math.max(0, Math.min(verifiedFiles, activePackagePlan.files.size()));

            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.isDirectory()) {
                throw new IOException("无法读取选择的碎片文件夹。");
            }

            publishPackageState("verifying", "正在核对碎片…", 0, 0, 0, true, null);
            PackageFragmentImporter.Result result = new PackageFragmentImporter(getContentResolver())
                .importFrom(root, activePackagePlan, new PackageFragmentImporter.Host() {
                    @Override
                    public void checkControlSignals() throws Exception {
                        GameDownloadService.this.checkControlSignals();
                    }

                    @Override
                    public File targetFor(GamePackagePlan.FileEntry entry) {
                        return packageFileTarget(entry, entriesDir, apkTarget, preparedDir);
                    }

                    @Override
                    public void publishProgress(String message, int index, int total, boolean force) {
                        verifiedFiles = Math.max(verifiedFiles, index);
                        publishPackageState("verifying", message, downloadedBytesForPackage(),
                            packagePercent(), Math.min(index, total), force, null);
                    }
                });

            if (!result.isComplete()) {
                terminalStatus = "paused";
                terminalMessage = describeImportGaps(result);
                return;
            }

            checkControlSignals();
            publishPackageState("extracting", "正在组装 OBB 并准备 APK", downloadedBytesForPackage(),
                90.0, activePackagePlan.files.size(), true, null);
            terminalPrepared = preparePackageArtifacts(entriesDir, apkTarget, obbTarget);
            DownloadFileUtils.deleteRecursively(workDir);
            terminalStatus = "ready";
            terminalMessage = "碎片导入完成，APK 和 OBB 已准备完成";
        } catch (PausedException ignored) {
            terminalStatus = "paused";
            terminalMessage = "导入已暂停，已经对上的文件会保留";
        } catch (CancelledException ignored) {
            cancelled = true;
        } catch (Exception error) {
            terminalStatus = "error";
            terminalMessage = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            finishPackageWorker(terminalStatus, terminalMessage, terminalPrepared, cancelled);
            RUNNING.set(false);
            stopForeground(false);
            stopSelfResult(startId);
        }
    }

    /** 缺哪些、哪些对不上 —— 只列前三个，没必要把几十条路径塞给玩家。 */
    private static String describeImportGaps(PackageFragmentImporter.Result result) {
        List<String> parts = new ArrayList<>();
        if (!result.missing.isEmpty()) {
            parts.add("缺 " + result.missing.size() + " 个（" + joinFirstPaths(result.missing) + "）");
        }
        if (!result.mismatched.isEmpty()) {
            parts.add("有 " + result.mismatched.size() + " 个内容对不上（" + joinFirstPaths(result.mismatched) + "）");
        }
        return "碎片还不齐：" + String.join("；", parts) + "。补齐后再点一次导入，已经对上的不会重拷。";
    }

    private static String joinFirstPaths(List<String> paths) {
        return String.join("、", paths.subList(0, Math.min(3, paths.size())));
    }

    /** 只清同一套下载根目录里的历史残留；不同版本的工作目录直接删掉。 */
    private void preparePackageWork(File downloadsRoot, File currentWorkDir, File preparedDir) throws IOException {
        File[] children = downloadsRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().startsWith("package-") && !child.equals(currentWorkDir)) {
                    DownloadFileUtils.deleteRecursively(child);
                }
            }
        }
        DownloadFileUtils.ensureDirectory(currentWorkDir);
        DownloadFileUtils.ensureDirectory(preparedDir);
    }

    private File packageFileTarget(GamePackagePlan.FileEntry entry, File entriesDir, File apkTarget, File preparedDir) {
        if (entry.isApk()) {
            return apkTarget;
        }
        if (entry.isObbEntry()) {
            return new File(entriesDir, entry.path.replace('/', File.separatorChar));
        }
        return new File(preparedDir, new File(entry.path).getName());
    }

    private void downloadPackageFiles(File entriesDir, File apkTarget, File preparedDir) throws Exception {
        int total = activePackagePlan.files.size();
        int index = 0;
        for (GamePackagePlan.FileEntry entry : activePackagePlan.files) {
            checkControlSignals();
            index++;
            File target = packageFileTarget(entry, entriesDir, apkTarget, preparedDir);
            if (target.isFile() && target.length() == entry.sizeBytes
                && DownloadFileUtils.hashMatches(target, entry.sha256)) {
                verifiedFiles = Math.max(verifiedFiles, index);
                publishPackageState("verifying", "已校验 " + index + " / " + total + " 个文件",
                    downloadedBytesForPackage(), packagePercent(), index, true, null);
                continue;
            }
            DownloadFileUtils.deleteFile(target);

            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                checkControlSignals();
                try {
                    packageDownloader.download(entry, target);
                    if (target.length() != entry.sizeBytes) {
                        throw new IOException("文件大小不正确：" + entry.path);
                    }
                    publishPackageState("verifying", "正在校验 " + new File(entry.path).getName(),
                        downloadedBytesForPackage(), packagePercent(), index, true, null);
                    if (!DownloadFileUtils.hashMatches(target, entry.sha256)) {
                        DownloadFileUtils.deleteFile(target);
                        throw new IOException("校验失败：" + entry.path);
                    }
                    verifiedFiles = index;
                    lastError = null;
                    break;
                } catch (PausedException | CancelledException control) {
                    throw control;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt < MAX_ATTEMPTS) {
                        publishPackageState("downloading",
                            "连接中断，正在重试 " + (attempt + 1) + " / " + MAX_ATTEMPTS,
                            downloadedBytesForPackage(), packagePercent(), index, true, null);
                        sleepWithControl(500L * attempt);
                    }
                }
            }
            if (lastError != null) {
                throw new IOException("下载失败：" + entry.path + "：" + lastError.getMessage(), lastError);
            }
            publishPackageState("downloading", "已完成 " + index + " / " + total + " 个文件",
                downloadedBytesForPackage(), packagePercent(), index, true, null);
        }
    }

    /**
     * APK 在下载时已经直接落到 prepared，OBB 在这里按旁车顺序组装：
     * 这次下过的条目用下载件，没下的从已安装的 OBB 里搬（见 ObbAssembler 注释）。
     */
    private PreparedFiles preparePackageArtifacts(File entriesDir, File apkTarget, File obbTarget) throws Exception {
        if (!apkTarget.isFile() || apkTarget.length() <= 0) {
            throw new IOException("没有找到下载好的 APK");
        }
        if (obbTarget != null && !activePackagePlan.obbEntries.isEmpty()) {
            Map<String, File> staged = new HashMap<>();
            for (String entryPath : activePackagePlan.obbEntries) {
                File stagedFile = new File(entriesDir, entryPath.replace('/', File.separatorChar));
                if (stagedFile.isFile()) {
                    staged.put(entryPath, stagedFile);
                }
            }
            new ObbAssembler(new ObbAssembler.Host() {
                @Override
                public void publishProgress(String message, boolean force) {
                    publishPackageState("extracting", message, downloadedBytesForPackage(), 95.0,
                        activePackagePlan.files.size(), force, null);
                }

                @Override
                public void checkControlSignals() throws Exception {
                    GameDownloadService.this.checkControlSignals();
                }
            }).assemble(obbTarget, activePackagePlan.obbEntries, staged, obbTarget);
            if (obbTarget.getParentFile() != null) {
                installer.removeStaleObbFiles(obbTarget.getParentFile(), obbTarget);
            }
        }
        return new PreparedFiles(apkTarget, obbTarget, UUID.randomUUID().toString());
    }

    private void publishPackageProgress(String message, boolean force) {
        publishPackageState("downloading", message, downloadedBytesForPackage(), packagePercent(),
            Math.min(verifiedFiles + 1, activePackagePlan == null ? 0 : activePackagePlan.files.size()),
            force, null);
    }

    private long downloadedBytesForPackage() {
        if (activePackagePlan == null) {
            return 0L;
        }
        File downloadsRoot = LauncherStorage.downloadsRoot(this);
        File entriesDir = new File(new File(downloadsRoot, "package-" + activePackagePlan.version), "entries");
        File preparedDir = new File(downloadsRoot, "prepared");
        File apkTarget = new File(preparedDir, "CrossingVoid-latest.apk");
        long total = 0L;
        for (GamePackagePlan.FileEntry entry : activePackagePlan.files) {
            File target = packageFileTarget(entry, entriesDir, apkTarget, preparedDir);
            if (target.isFile()) {
                total += Math.min(entry.sizeBytes, Math.max(0L, target.length()));
            }
        }
        return total;
    }

    private long missingPackageBytes(File entriesDir, File apkTarget, File preparedDir) {
        if (activePackagePlan == null) {
            return 0L;
        }
        long total = 0L;
        for (GamePackagePlan.FileEntry entry : activePackagePlan.files) {
            File target = packageFileTarget(entry, entriesDir, apkTarget, preparedDir);
            if (target.isFile() && target.length() == entry.sizeBytes) {
                continue;
            }
            total += entry.sizeBytes;
        }
        return total;
    }

    /** 组装 OBB 的额外空间：新 OBB 要写一份完整大小。 */
    private long packageObbBytes() {
        if (activePackagePlan == null || activePackagePlan.obbEntries.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (String entryPath : activePackagePlan.obbEntries) {
            GamePackagePlan.FileEntry entry = activePackagePlan.fileByPath(entryPath);
            if (entry != null) {
                total += entry.sizeBytes;
            }
        }
        return total;
    }

    private double packagePercent() {
        if (activePackagePlan == null || activePackagePlan.totalBytes <= 0) {
            return 0.0;
        }
        return Math.min(85.0, downloadedBytesForPackage() / (double) activePackagePlan.totalBytes * 85.0);
    }

    private void publishPackageState(String status, String message, long downloadedBytes, double percent,
        int currentFile, boolean force, PreparedFiles prepared) {
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
            state.put("version", activePackagePlan == null ? "" : activePackagePlan.version);
            state.put("source", activePackagePlan == null ? "" : activePackagePlan.source);
            state.put("archiveSha256", activePackagePlan == null ? "" : activePackagePlan.apkSha256());
            state.put("downloadedBytes", Math.max(0L, downloadedBytes));
            state.put("totalBytes", activePackagePlan == null ? 0L : activePackagePlan.totalBytes);
            state.put("percent", Math.max(0.0, Math.min(100.0, percent)));
            state.put("currentChunk", Math.max(0, currentFile));
            state.put("totalChunks", activePackagePlan == null ? 0 : activePackagePlan.files.size());
            state.put("downloadedFiles", Math.max(0, currentFile));
            state.put("totalFiles", activePackagePlan == null ? 0 : activePackagePlan.files.size());
            state.put("verifiedChunks", verifiedFiles);
            state.put("bytesPerSecond", bytesPerSecond);
            state.put("canPause", status.equals("downloading"));
            state.put("updatedAt", now);
            if (prepared != null) {
                state.put("apkPath", prepared.apk.getAbsolutePath());
                if (prepared.obb != null) {
                    state.put("obbPath", prepared.obb.getAbsolutePath());
                    state.put("obbFileName", prepared.obb.getName());
                }
                state.put("installToken", prepared.installToken);
            }
            if ("ready".equals(status) && activePackagePlan != null) {
                JSONArray packageFiles = new JSONArray();
                for (GamePackagePlan.FileEntry entry : activePackagePlan.manifestFiles) {
                    JSONObject item = new JSONObject();
                    item.put("path", entry.path);
                    item.put("sizeBytes", entry.sizeBytes);
                    item.put("sha256", entry.sha256);
                    packageFiles.put(item);
                }
                state.put("packageFiles", packageFiles);
                state.put("productKey", activePackagePlan.productKey);
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        String logSignature = status + "|" + currentFile + "|" + verifiedFiles + "|" + message;
        if (!logSignature.equals(lastLoggedStateSignature)) {
            lastLoggedStateSignature = logSignature;
            try {
                LauncherLogStore.append(this, status.equals("error") ? "error" : "info",
                    "game-package.state", message, state.toString());
            } catch (Exception ignored) {
            }
        }
        saveAndBroadcastState(state);
        if (status.equals("downloading") || status.equals("verifying") || status.equals("extracting")) {
            notifier.update((int) Math.round(percent));
        }
    }

    private void finishPackageWorker(String status, String message, PreparedFiles prepared, boolean cancelled) {
        PAUSE_REQUESTED.set(false);
        CANCEL_REQUESTED.set(false);
        if (cancelled) {
            clearAllDownloads(this);
            saveAndBroadcastState(idleState());
            notifier.cancelActive();
            return;
        }
        String terminalStatus = status == null ? "error" : status;
        String terminalMessage = message == null || message.isBlank() ? "游戏下载任务意外结束" : message;
        if (terminalStatus.equals("idle")) {
            saveAndBroadcastState(idleState());
            return;
        }
        long bytes = terminalStatus.equals("ready") && activePackagePlan != null
            ? activePackagePlan.totalBytes
            : downloadedBytesForPackage();
        double percent = terminalStatus.equals("ready") ? 100.0 : packagePercent();
        int fileIndex = terminalStatus.equals("ready") && activePackagePlan != null
            ? activePackagePlan.files.size()
            : Math.min(verifiedFiles + 1, activePackagePlan == null ? 0 : activePackagePlan.files.size());
        publishPackageState(terminalStatus, terminalMessage, bytes, percent, fileIndex, true, prepared);
        if (terminalStatus.equals("ready")) notifier.showCompletion();
        else if (terminalStatus.equals("paused")) notifier.showPaused();
        else if (terminalStatus.equals("error")) notifier.showError(terminalMessage);
    }

    private static final class PausedException extends Exception {
    }

    private static final class CancelledException extends Exception {
    }
}
