package com.lingjing.launcher.android;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 下载完成之后的落地环节：合并分片、校验整包、解压 APK 与 OBB。
 *
 * 不持有服务状态：进度与暂停/取消信号都通过 Progress 回传，
 * 这样这一层可以脱离 Service 被单独读懂和替换。
 */
final class PackageInstaller {
    interface Progress {
        void publish(String status, String message, long downloadedBytes, double percent, int currentChunk, boolean force);

        void checkControlSignals() throws Exception;
    }

    private final Progress progress;

    PackageInstaller(Progress progress) {
        this.progress = progress;
    }

    void mergeChunks(DownloadPlan plan, File chunksDir, File archiveFile) throws Exception {
        progress.publish("merging", "正在合并下载分片", plan.totalBytes, 85.0, plan.chunks.size(), true);
        File temporary = new File(archiveFile.getParentFile(), archiveFile.getName() + ".merging");
        DownloadFileUtils.deleteFile(temporary);
        long copied = 0L;
        byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
            for (DownloadChunk chunk : plan.chunks) {
                progress.checkControlSignals();
                File part = new File(chunksDir, chunk.fileName);
                if (part.length() != chunk.sizeBytes) {
                    throw new IOException("合并前发现第 " + chunk.index + " 片不完整");
                }
                try (InputStream input = new BufferedInputStream(new FileInputStream(part), DownloadFileUtils.BUFFER_SIZE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        progress.checkControlSignals();
                        if (read == 0) {
                            continue;
                        }
                        output.write(buffer, 0, read);
                        copied += read;
                        double percent = 85.0 + (copied / (double) plan.totalBytes) * 5.0;
                        progress.publish("merging", "正在合并第 " + chunk.index + " / " + chunk.count + " 片", plan.totalBytes, percent, chunk.index, false);
                    }
                }
                DownloadFileUtils.deleteFile(part);
            }
        }
        if (temporary.length() != plan.totalBytes) {
            throw new IOException("合并后的完整包大小不正确");
        }
        DownloadFileUtils.deleteFile(archiveFile);
        if (!temporary.renameTo(archiveFile)) {
            throw new IOException("无法保存合并后的完整包");
        }
    }

    void verifyArchive(DownloadPlan plan, File archiveFile) throws Exception {
        progress.publish("verifying", "正在校验完整安装包", plan.totalBytes, 90.0, plan.chunks.size(), true);
        String actual = sha256WithProgress(plan, archiveFile, 90.0, 5.0);
        if (!actual.equalsIgnoreCase(plan.archiveSha256)) {
            DownloadFileUtils.deleteFile(archiveFile);
            throw new IOException("完整安装包 SHA256 校验失败，请重新下载");
        }
    }

    PreparedFiles extractPackage(DownloadPlan plan, File archiveFile, File downloadsRoot, File obbDir) throws Exception {
        progress.publish("extracting", "正在解压 APK 和 OBB", plan.totalBytes, 95.0, plan.chunks.size(), true);
        File preparedDir = new File(downloadsRoot, "prepared");
        if (obbDir == null) {
            throw new IOException("系统没有提供可用的游戏 OBB 目录");
        }
        DownloadFileUtils.ensureDirectory(obbDir);
        File apkOutput = new File(preparedDir, "CrossingVoid-latest.apk");
        File obbOutput = null;
        long extracted = 0L;
        byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archiveFile), DownloadFileUtils.BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                progress.checkControlSignals();
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

                DownloadFileUtils.ensureDirectory(target.getParentFile());
                File temporary = new File(target.getParentFile(), target.getName() + ".extracting");
                DownloadFileUtils.deleteFile(temporary);
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        progress.checkControlSignals();
                        if (read == 0) {
                            continue;
                        }
                        output.write(buffer, 0, read);
                        extracted += read;
                        double percent = 95.0 + Math.min(1.0, extracted / (double) plan.totalBytes) * 5.0;
                        progress.publish("extracting", "正在解压 " + new File(name).getName(), plan.totalBytes, percent, plan.chunks.size(), false);
                    }
                }
                DownloadFileUtils.deleteFile(target);
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

    void removeStaleObbFiles(File obbDir, File currentObb) {
        File[] files = obbDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!file.equals(currentObb) && file.isFile() && (name.startsWith("main.") || name.startsWith("patch.")) && name.endsWith(".obb")) {
                file.delete();
            }
        }
    }

    private String sha256WithProgress(DownloadPlan plan, File file, double basePercent, double spanPercent) throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前设备不支持 SHA-256", error);
        }
        long processed = 0L;
        byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), DownloadFileUtils.BUFFER_SIZE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                progress.checkControlSignals();
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                processed += read;
                double percent = basePercent + Math.min(1.0, processed / (double) Math.max(1L, file.length())) * spanPercent;
                progress.publish("verifying", "正在校验完整安装包", plan.totalBytes, percent, plan.chunks.size(), false);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
