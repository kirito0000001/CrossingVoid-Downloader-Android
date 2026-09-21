package com.lingjing.launcher.android;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 清单 v1 的单个文件下载：断点续传 + 重定向 + 逐块进度。
 *
 * 与老的 {@link ChunkDownloader} 的区别只有两点：
 * 1. 下载地址直接来自清单的 `baseUrl + path`，**不再走签名接口**（下载站是公开的）；
 * 2. 落位文件名就是清单里的相对路径，方便按 path 组装 OBB。
 *
 * 重试与暂停/取消由调用方（GameDownloadService）负责。
 */
final class PackageFileDownloader {

    interface Host {
        void publishProgress(String message, boolean force);

        void checkControlSignals() throws Exception;
    }

    private final Host host;
    private final String userAgent;

    PackageFileDownloader(Host host, String userAgent) {
        this.host = host;
        this.userAgent = userAgent;
    }

    /**
     * 下载到 target；target 已存在时按 Range 续传。
     *
     * 按候选地址顺序换源：首选源上没有这个文件（404）就直接换下一个源，
     * 这样"双源混用"是天然的 —— 一次会话里不同文件可以来自不同源。
     * 每个候选只试一次，重试由调用方（GameDownloadService）统一负责。
     */
    void download(GamePackagePlan.FileEntry entry, File target) throws Exception {
        Exception lastError = null;
        for (String candidate : entry.urls) {
            try {
                downloadOnce(candidate, entry, target);
                return;
            } catch (Exception error) {
                lastError = error;
            }
        }
        throw new IOException(
            "所有下载源都失败：" + entry.path + "："
                + (lastError == null || lastError.getMessage() == null ? "没有可用地址" : lastError.getMessage()),
            lastError);
    }

    private void downloadOnce(String url, GamePackagePlan.FileEntry entry, File target) throws Exception {
        long resumeFrom = target.exists() ? target.length() : 0L;
        if (resumeFrom > entry.sizeBytes) {
            DownloadFileUtils.deleteFile(target);
            resumeFrom = 0L;
        }
        if (resumeFrom == entry.sizeBytes) {
            return; // 已经下满，交给调用方校验 sha256
        }

        DownloadFileUtils.ensureDirectory(target.getParentFile());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/octet-stream");
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        }

        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK && resumeFrom > 0) {
                // 服务端不支持 Range：从头写。
                resumeFrom = 0L;
            } else if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("下载服务器返回 HTTP " + status + "：" + entry.path);
            }

            try (
                InputStream input = new BufferedInputStream(connection.getInputStream(), DownloadFileUtils.BUFFER_SIZE);
                RandomAccessFile output = new RandomAccessFile(target, "rw")
            ) {
                if (resumeFrom == 0L) {
                    output.setLength(0L);
                }
                output.seek(resumeFrom);
                byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    host.checkControlSignals();
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    host.publishProgress("正在下载 " + new File(entry.path).getName(), false);
                }
            }
        } finally {
            connection.disconnect();
        }
    }
}
