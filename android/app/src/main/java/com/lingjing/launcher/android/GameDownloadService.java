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
    public static final String ACTION_IMPORT = "com.lingjing.launcher.android.action.IMPORT_CHUNKS";
    public static final String ACTION_EXPORT = "com.lingjing.launcher.android.action.EXPORT_CHUNKS";
    public static final String ACTION_STATE = "com.lingjing.launcher.android.action.DOWNLOAD_STATE";
    public static final String EXTRA_PLAN = "downloadPlan";
    public static final String EXTRA_STATE = "downloadState";
    public static final String EXTRA_IMPORT_TREE_URI = "importTreeUri";
    public static final String EXTRA_EXPORT_TREE_URI = "exportTreeUri";

    private static final String UPDATE_API = "https://www.crossingvoid.top/api/toolbox-updates/sign-download";
    private static final String PREFS_NAME = "crossingvoid_download";
    private static final String PREF_STATE = "state";
    private static final String PREF_PLAN = "plan";
    private static final String PREF_MANAGED_VERSION = "managedVersion";
    private static final int MAX_ATTEMPTS = 3;
    private static final long STATE_INTERVAL_MS = 350L;
    private static final String RECOVERY_MANIFEST_FILE = "零境启动器恢复信息.json";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean PAUSE_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);
    private static final Object STATE_LOCK = new Object();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private long lastStateAt;
    private long lastRateBytes;
    private long lastRateAt;
    private double bytesPerSecond;
    private DownloadPlan activePlan;
    private int verifiedChunks;
    private String lastLoggedStateSignature = "";
    private DownloadNotifier notifier;
    private PackageInstaller installer;

    @Override
    public void onCreate() {
        super.onCreate();
        notifier = DownloadNotifier.attach(this);
        notifier.createChannel();
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

        if (ACTION_EXPORT.equals(action)) {
            String exportTreeUri = intent == null ? null : intent.getStringExtra(EXTRA_EXPORT_TREE_URI);
            String exportPlan = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PLAN, "");
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
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_PLAN, importPlan).apply();
            notifier.startForeground(0);
            executor.execute(() -> runImport(importPlan, Uri.parse(importTreeUri), startId));
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

        if (!RUNNING.compareAndSet(false, true)) {
            return START_REDELIVER_INTENT;
        }
        PAUSE_REQUESTED.set(false);
        CANCEL_REQUESTED.set(false);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_PLAN, planJson).apply();
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
            File downloadsRoot = getDownloadsRoot(this);
            File workDir = new File(downloadsRoot, "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            File archiveFile = new File(workDir, activePlan.archiveFileName);
            DownloadFileUtils.ensureDirectory(chunksDir);
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
            File downloadsRoot = getDownloadsRoot(this);
            File workDir = new File(downloadsRoot, "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            DownloadFileUtils.ensureDirectory(chunksDir);
            prepareForPlan(downloadsRoot, workDir, activePlan);
            DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
            if (root == null || !root.isDirectory()) throw new IOException("无法读取选择的游戏碎片文件夹。");
            List<DocumentFile> sourceFiles = new ArrayList<>();
            collectChunkDocuments(root, sourceFiles);
            if (sourceFiles.isEmpty()) {
                terminalPrepared = importPreparedRecoveryFiles(root, activePlan, downloadsRoot);
                terminalStatus = "ready";
                terminalMessage = "恢复文件校验完成，APK 和 OBB 已准备完成";
                verifiedChunks = activePlan.chunks.size();
                return;
            }
            int imported = 0;
            for (DocumentFile sourceFile : sourceFiles) {
                checkControlSignals();
                String name = sourceFile.getName();
                DownloadChunk chunk = findChunkByName(name);
                if (chunk == null) continue;
                File destination = new File(chunksDir, chunk.fileName);
                if (destination.length() == chunk.sizeBytes && hashMatches(destination, chunk.sha256)) {
                    continue;
                }
                DownloadFileUtils.deleteFile(destination);
                copyAndVerifyImportedChunk(sourceFile, chunk, destination, chunksDir);
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
            File workDir = new File(getDownloadsRoot(this), "work-" + activePlan.archiveSha256.substring(0, 12));
            File chunksDir = new File(workDir, "chunks");
            if (allChunksAvailable(activePlan, chunksDir)) {
                exportVerifiedChunks(root, activePlan, chunksDir);
                message = "游戏碎片已导出，可以卸载旧启动器";
            } else {
                exportPreparedRecoveryFiles(root, previousState);
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
            restoreStateAfterExport(previousState, success, message);
            RUNNING.set(false);
            stopForeground(false);
            stopSelfResult(startId);
        }
    }

    private boolean allChunksAvailable(DownloadPlan plan, File chunksDir) throws IOException {
        for (DownloadChunk chunk : plan.chunks) {
            File source = new File(chunksDir, chunk.fileName);
            if (source.length() != chunk.sizeBytes || !hashMatches(source, chunk.sha256)) return false;
        }
        return true;
    }

    private void exportVerifiedChunks(DocumentFile root, DownloadPlan plan, File chunksDir) throws Exception {
        long copiedBefore = 0L;
        for (DownloadChunk chunk : plan.chunks) {
            File source = new File(chunksDir, chunk.fileName);
            copyAndVerifyExportedFile(source, root, chunk.fileName, chunk.sha256, chunk.sizeBytes,
                copiedBefore, plan.totalBytes, chunk.index, chunk.count);
            copiedBefore += chunk.sizeBytes;
        }
    }

    private void exportPreparedRecoveryFiles(DocumentFile root, JSONObject previousState) throws Exception {
        File apk = new File(previousState.optString("apkPath", ""));
        File obb = new File(previousState.optString("obbPath", ""));
        if (!apk.isFile() || !obb.isFile()) {
            throw new IOException("旧版已经清理了游戏碎片，并且没有找到完整 APK 或 OBB。需要重新下载游戏。");
        }
        long total = Math.addExact(apk.length(), obb.length());
        ExportedFile apkExport = copyAndVerifyExportedFile(apk, root, apk.getName(), "", apk.length(), 0L, total, 1, 2);
        ExportedFile obbExport = copyAndVerifyExportedFile(obb, root, obb.getName(), "", obb.length(), apk.length(), total, 2, 2);
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("kind", "crossingvoid-prepared-recovery");
        manifest.put("version", activePlan.version);
        manifest.put("archiveSha256", activePlan.archiveSha256);
        manifest.put("apk", apkExport.toJson());
        manifest.put("obb", obbExport.toJson());
        writeDocumentBytes(root, RECOVERY_MANIFEST_FILE, manifest.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private ExportedFile copyAndVerifyExportedFile(File source, DocumentFile root, String fileName, String expectedSha256,
                                                    long expectedSize, long copiedBefore, long totalBytes,
                                                    int itemIndex, int itemCount) throws Exception {
        if (!source.isFile() || source.length() != expectedSize) throw new IOException("导出源文件不完整：" + fileName);
        String temporaryName = fileName + ".exporting";
        deleteDocument(root.findFile(temporaryName));
        DocumentFile tempFile = root.createFile("application/octet-stream", temporaryName);
        if (tempFile == null) throw new IOException("无法创建导出文件：" + temporaryName);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0L;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source), DownloadFileUtils.BUFFER_SIZE);
             OutputStream rawOutput = getContentResolver().openOutputStream(tempFile.getUri(), "wt");
             OutputStream output = rawOutput == null ? null : new BufferedOutputStream(rawOutput, DownloadFileUtils.BUFFER_SIZE)) {
            if (output == null) throw new IOException("无法写入导出文件：" + temporaryName);
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkControlSignals();
                if (read == 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                long processed = copiedBefore + copied;
                publishState("exporting", "正在导出第 " + itemIndex + " / " + itemCount + " 项：" + fileName,
                    processed, processed / (double) Math.max(1L, totalBytes) * 100.0, itemIndex, false, null);
            }
        }
        String actualHash = toHex(digest.digest());
        String storedHash = sha256(tempFile);
        if (copied != expectedSize || tempFile.length() != expectedSize || !actualHash.equalsIgnoreCase(storedHash)
            || (!expectedSha256.isBlank() && !actualHash.equalsIgnoreCase(expectedSha256))) {
            deleteDocument(tempFile);
            throw new IOException("导出文件校验失败：" + fileName);
        }
        deleteDocument(root.findFile(fileName));
        if (!tempFile.renameTo(fileName)) {
            deleteDocument(tempFile);
            throw new IOException("无法保存导出文件：" + fileName);
        }
        return new ExportedFile(fileName, expectedSize, actualHash);
    }

    private void writeDocumentBytes(DocumentFile root, String fileName, byte[] content) throws IOException {
        String temporaryName = fileName + ".exporting";
        deleteDocument(root.findFile(temporaryName));
        DocumentFile temporary = root.createFile("application/json", temporaryName);
        if (temporary == null) throw new IOException("无法创建恢复信息文件。");
        try (OutputStream output = getContentResolver().openOutputStream(temporary.getUri(), "wt")) {
            if (output == null) throw new IOException("无法写入恢复信息文件。");
            output.write(content);
        }
        deleteDocument(root.findFile(fileName));
        if (!temporary.renameTo(fileName)) throw new IOException("无法保存恢复信息文件。");
    }

    private String sha256(DocumentFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream rawInput = getContentResolver().openInputStream(file.getUri());
             InputStream input = rawInput == null ? null : new BufferedInputStream(rawInput, DownloadFileUtils.BUFFER_SIZE)) {
            if (input == null) throw new IOException("无法重新读取导出文件：" + file.getName());
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private void deleteDocument(DocumentFile file) throws IOException {
        if (file != null && file.exists() && !file.delete()) throw new IOException("无法覆盖导出文件：" + file.getName());
    }

    private void restoreStateAfterExport(JSONObject previousState, boolean success, String message) {
        JSONObject restored;
        try {
            restored = new JSONObject(previousState.toString());
            restored.put("status", success ? "ready" : "error");
            restored.put("message", message);
            restored.put("canPause", false);
            restored.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException error) {
            restored = errorState(message);
        }
        saveAndBroadcastState(restored);
        if (success) notifier.showCompletion();
        else notifier.showError(message);
    }

    private PreparedFiles importPreparedRecoveryFiles(DocumentFile root, DownloadPlan plan, File downloadsRoot) throws Exception {
        DocumentFile manifestFile = findDocumentByName(root, RECOVERY_MANIFEST_FILE);
        if (manifestFile == null) {
            throw new IOException("所选文件夹中没有当前版本的游戏碎片或零境启动器恢复文件。");
        }
        JSONObject manifest = new JSONObject(readDocumentText(manifestFile));
        if (manifest.optInt("schemaVersion") != 1
            || !"crossingvoid-prepared-recovery".equals(manifest.optString("kind"))
            || !plan.version.equals(manifest.optString("version"))
            || !plan.archiveSha256.equalsIgnoreCase(manifest.optString("archiveSha256"))) {
            throw new IOException("恢复信息与当前游戏版本不匹配。");
        }
        JSONObject apkInfo = manifest.getJSONObject("apk");
        JSONObject obbInfo = manifest.getJSONObject("obb");
        String apkName = apkInfo.getString("fileName");
        String obbName = obbInfo.getString("fileName");
        if (!obbName.matches("^(main|patch)\\.\\d+\\.com\\.TFAC\\.CorssingVoid\\.obb$")) {
            throw new IOException("恢复信息中的 OBB 文件名不正确。");
        }
        DocumentFile apkSource = findDocumentByName(root, apkName);
        DocumentFile obbSource = findDocumentByName(root, obbName);
        if (apkSource == null || obbSource == null) throw new IOException("恢复文件夹缺少 APK 或 OBB。");

        File preparedDir = new File(downloadsRoot, "prepared");
        File obbDir = getObbDir();
        if (obbDir == null) throw new IOException("系统没有提供可用的游戏 OBB 目录");
        DownloadFileUtils.ensureDirectory(preparedDir);
        DownloadFileUtils.ensureDirectory(obbDir);
        File apkDestination = new File(preparedDir, "CrossingVoid-latest.apk");
        File obbDestination = new File(obbDir, obbName);
        long apkSize = apkInfo.getLong("sizeBytes");
        long obbSize = obbInfo.getLong("sizeBytes");
        long total = Math.addExact(apkSize, obbSize);
        copyAndVerifyRecoveryDocument(apkSource, apkDestination, apkSize, apkInfo.getString("sha256"), 0L, total, 1, 2);
        copyAndVerifyRecoveryDocument(obbSource, obbDestination, obbSize, obbInfo.getString("sha256"), apkSize, total, 2, 2);
        installer.removeStaleObbFiles(obbDir, obbDestination);
        return new PreparedFiles(apkDestination, obbDestination, UUID.randomUUID().toString());
    }

    private void copyAndVerifyRecoveryDocument(DocumentFile source, File destination, long expectedSize,
                                               String expectedSha256, long copiedBefore, long totalBytes,
                                               int itemIndex, int itemCount) throws Exception {
        if (expectedSize <= 0 || !expectedSha256.matches("^[a-fA-F0-9]{64}$")) {
            throw new IOException("恢复文件校验信息不完整：" + source.getName());
        }
        DownloadFileUtils.ensureDirectory(destination.getParentFile());
        File temporary = new File(destination.getParentFile(), destination.getName() + ".importing");
        DownloadFileUtils.deleteFile(temporary);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0L;
        try (InputStream rawInput = getContentResolver().openInputStream(source.getUri());
             InputStream input = rawInput == null ? null : new BufferedInputStream(rawInput, DownloadFileUtils.BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
            if (input == null) throw new IOException("无法读取恢复文件：" + source.getName());
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkControlSignals();
                if (read == 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                long processed = copiedBefore + copied;
                publishState("importing", "正在恢复第 " + itemIndex + " / " + itemCount + " 项：" + source.getName(),
                    processed, processed / (double) Math.max(1L, totalBytes) * 100.0, itemIndex, false, null);
            }
        }
        String actualHash = toHex(digest.digest());
        if (copied != expectedSize || temporary.length() != expectedSize || !actualHash.equalsIgnoreCase(expectedSha256)) {
            DownloadFileUtils.deleteFile(temporary);
            throw new IOException("恢复文件校验失败：" + source.getName());
        }
        DownloadFileUtils.deleteFile(destination);
        if (!temporary.renameTo(destination)) throw new IOException("无法保存恢复文件：" + destination.getName());
    }

    private DocumentFile findDocumentByName(DocumentFile directory, String name) {
        for (DocumentFile child : directory.listFiles()) {
            if (child.isDirectory()) {
                DocumentFile nested = findDocumentByName(child, name);
                if (nested != null) return nested;
            } else if (child.isFile() && name.equals(child.getName())) {
                return child;
            }
        }
        return null;
    }

    private String readDocumentText(DocumentFile file) throws IOException {
        if (file.length() <= 0 || file.length() > 64L * 1024L) throw new IOException("恢复信息文件大小异常。");
        try (InputStream input = getContentResolver().openInputStream(file.getUri());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("无法读取恢复信息文件。");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
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

    private void collectChunkDocuments(DocumentFile directory, List<DocumentFile> result) {
        for (DocumentFile child : directory.listFiles()) {
            if (child.isDirectory()) {
                collectChunkDocuments(child, result);
            } else if (child.isFile() && findChunkByName(child.getName()) != null) {
                result.add(child);
            }
        }
    }

    private DownloadChunk findChunkByName(String name) {
        if (name == null || !name.matches("^CrossingVoid手机端\\.碎片\\d{3}$")) return null;
        for (DownloadChunk chunk : activePlan.chunks) {
            if (chunk.fileName.equals(name)) return chunk;
        }
        return null;
    }

    private void copyAndVerifyImportedChunk(DocumentFile source, DownloadChunk chunk, File destination, File chunksDir) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".importing");
        DownloadFileUtils.deleteFile(temporary);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        long copied = 0L;
        long existing = existingChunkBytes(activePlan, chunksDir);
        try (InputStream sourceInput = getContentResolver().openInputStream(source.getUri());
             InputStream bufferedInput = sourceInput == null ? null : new BufferedInputStream(sourceInput, DownloadFileUtils.BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
            if (bufferedInput == null) throw new IOException("无法读取导入的碎片：" + chunk.fileName);
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = bufferedInput.read(buffer)) >= 0) {
                checkControlSignals();
                if (read <= 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                long processed = Math.min(activePlan.totalBytes, existing + copied);
                publishState("importing", "正在导入并校验第 " + chunk.index + " / " + chunk.count + " 片", processed,
                    processed / (double) activePlan.totalBytes * 85.0, chunk.index, true, null);
            }
        }
        String actualHash = toHex(digest.digest());
        if (copied != chunk.sizeBytes || !actualHash.equalsIgnoreCase(chunk.sha256)) {
            DownloadFileUtils.deleteFile(temporary);
            throw new IOException("导入碎片校验失败：" + chunk.fileName);
        }
        DownloadFileUtils.deleteFile(destination);
        if (!temporary.renameTo(destination)) throw new IOException("无法保存导入碎片：" + destination.getName());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private int verifiedChunkCount(DownloadPlan plan, File chunksDir) throws IOException {
        int count = 0;
        for (DownloadChunk chunk : plan.chunks) {
            File file = new File(chunksDir, chunk.fileName);
            if (file.length() == chunk.sizeBytes && hashMatches(file, chunk.sha256)) count++;
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
            if (file.length() == chunk.sizeBytes && hashMatches(file, chunk.sha256)) {
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
                    downloadChunk(plan, chunk, file);
                    if (file.length() != chunk.sizeBytes) {
                        throw new IOException("第 " + chunk.index + " 片大小不正确");
                    }
                    publishState("verifying", "正在校验第 " + chunk.index + " / " + chunk.count + " 片", downloadedBytes(), currentPercent(), chunk.index, true, null);
                    if (!hashMatches(file, chunk.sha256)) {
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

    private void downloadChunk(DownloadPlan plan, DownloadChunk chunk, File outputFile) throws Exception {
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

            DownloadFileUtils.ensureDirectory(outputFile.getParentFile());
            try (
                InputStream input = new BufferedInputStream(connection.getInputStream(), DownloadFileUtils.BUFFER_SIZE);
                RandomAccessFile output = new RandomAccessFile(outputFile, "rw")
            ) {
                if (resumeFrom == 0) {
                    output.setLength(0);
                }
                output.seek(resumeFrom);
                byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
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

    private String signChunkUrl(DownloadPlan plan, DownloadChunk chunk) throws Exception {
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

    private static void copyPreparedStateFields(JSONObject target, JSONObject source) throws JSONException {
        for (String key : new String[] { "apkPath", "obbPath", "obbFileName", "installToken" }) {
            if (source.has(key)) target.put(key, source.optString(key));
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
        DownloadFileUtils.deleteRecursively(new File(getDownloadsRoot(context), "prepared"));
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
        DownloadFileUtils.deleteRecursively(getDownloadsRoot(context));
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

    private long existingChunkBytes(DownloadPlan plan, File chunksDir) {
        long total = 0L;
        for (DownloadChunk chunk : plan.chunks) {
            File file = new File(chunksDir, chunk.fileName);
            total += Math.min(chunk.sizeBytes, Math.max(0L, file.length()));
        }
        return total;
    }

    private long requiredAvailableBytes(DownloadPlan plan, File archiveFile, long existingChunkBytes) {
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

    private static File getDownloadsRoot(Context context) {
        File root = new File(context.getFilesDir(), "downloads");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static final class ExportedFile {
        final String fileName;
        final long sizeBytes;
        final String sha256;

        ExportedFile(String fileName, long sizeBytes, String sha256) {
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("fileName", fileName);
            result.put("sizeBytes", sizeBytes);
            result.put("sha256", sha256);
            return result;
        }
    }

    private static final class PausedException extends Exception {
    }

    private static final class CancelledException extends Exception {
    }
}
