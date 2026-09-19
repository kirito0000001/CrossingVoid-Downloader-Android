package com.lingjing.launcher.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 单个分片的下载：带断点续传、重定向与重试所需的 HTTP 细节。
 *
 * 不持有服务状态：进度与暂停/取消信号通过 Host 回传，
 * 签名接口需要的启动器版本由调用方传入。
 */
final class ChunkDownloader {
    private static final String UPDATE_API = "https://www.crossingvoid.top/api/toolbox-updates/sign-download";

    interface Host {
        void publishProgress(String message, boolean force);

        void checkControlSignals() throws Exception;
    }

    private final Host host;

    ChunkDownloader(Host host) {
        this.host = host;
    }

    void download(DownloadPlan plan, DownloadChunk chunk, File outputFile, String launcherVersion) throws Exception {
        String officialUrl = plan.source.equals("official") ? signChunkUrl(plan, chunk, launcherVersion) : "";
        String downloadUrl = DownloadFileUtils.resolveDownloadUrl(plan.source, chunk.downloadUrl, officialUrl);
        long resumeFrom = outputFile.exists() ? outputFile.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "CrossingVoidAndroidLauncher/" + launcherVersion);
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
                    host.checkControlSignals();
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    host.publishProgress("正在下载第 " + chunk.index + " / " + chunk.count + " 片", false);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private String signChunkUrl(DownloadPlan plan, DownloadChunk chunk, String launcherVersion) throws Exception {
        JSONObject request = new JSONObject();
        request.put("productKey", plan.productKey);
        request.put("version", plan.version);
        request.put("runtime", plan.runtime);
        request.put("objectKey", chunk.objectKey);
        request.put("launcherVersion", launcherVersion);

        HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_API).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "CrossingVoidAndroidLauncher/" + launcherVersion);
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
}
