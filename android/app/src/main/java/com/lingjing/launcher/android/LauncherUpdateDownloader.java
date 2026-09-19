package com.lingjing.launcher.android;

import android.content.Context;
import android.content.pm.Signature;
import android.os.Build;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 启动器更新包的下载、校验与就绪落状态。
 *
 * 不持有服务状态：暂停/取消信号、Context、进度与完成通知都通过 Host 回传，
 * 状态写入复用游戏下载那套 UpdateStateStore。
 */
final class LauncherUpdateDownloader {
    interface Host {
        void checkCancelled() throws Exception;

        Context context();

        void notifyProgress(int percent);

        void notifyCompletion(String versionName);
    }

    private static final long STATE_INTERVAL_MS = 350L;

    private final Host host;
    private long lastStateAt;

    LauncherUpdateDownloader(Host host) {
        this.host = host;
    }

    void download(UpdatePlan plan, File outputFile) throws Exception {
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
                InputStream input = new BufferedInputStream(connection.getInputStream(), DownloadFileUtils.BUFFER_SIZE);
                RandomAccessFile output = new RandomAccessFile(outputFile, "rw")
            ) {
                if (resumeFrom == 0L) output.setLength(0L);
                output.seek(resumeFrom);
                byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    host.checkCancelled();
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    publishDownloading(plan, output.length(), "正在下载启动器 " + plan.versionName, false);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    void publishDownloading(UpdatePlan plan, long downloaded, String message, boolean force) {
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
        UpdateStateStore.publishState(host.context(), state);
        host.notifyProgress((int) Math.round(state.optDouble("percent", 0.0)));
    }

    void validateDownloadedApk(File apk, UpdatePlan plan) throws Exception {
        PackageManager packageManager = host.context().getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !host.context().getPackageName().equals(candidate.packageName)) {
            throw new IOException("启动器安装包包名不正确");
        }
        if (LauncherUpdateVerifier.packageVersionCode(candidate) != plan.versionCode) {
            throw new IOException("启动器安装包 versionCode 不正确");
        }

        PackageInfo installed = packageManager.getPackageInfo(host.context().getPackageName(), flags);
        Signature[] installedSignatures = LauncherUpdateVerifier.packageSignatures(installed);
        Signature[] candidateSignatures = LauncherUpdateVerifier.packageSignatures(candidate);
        if (installedSignatures.length == 0 || candidateSignatures.length == 0 ||
            !LauncherUpdateVerifier.sameSignatures(installedSignatures, candidateSignatures)) {
            throw new IOException("启动器安装包签名不一致");
        }
    }

    void publishReady(UpdatePlan plan, File apk) {
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
        UpdateStateStore.publishState(host.context(), state);
    }

    static void putPlanIdentity(JSONObject state, UpdatePlan plan) {
        try {
            state.put("versionName", plan.versionName);
            state.put("versionCode", plan.versionCode);
            state.put("sha256", plan.sha256);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

}
