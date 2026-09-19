package com.lingjing.launcher.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class DownloadFileUtils {
    static final int BUFFER_SIZE = 256 * 1024;
    private static final long EXTRA_FREE_BYTES = 256L * 1024L * 1024L;

    private DownloadFileUtils() {
    }

    static long requiredFreeBytes(long archiveBytes) {
        if (archiveBytes <= 0) {
            return EXTRA_FREE_BYTES;
        }
        try {
            return Math.addExact(Math.multiplyExact(archiveBytes, 2L), EXTRA_FREE_BYTES);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    static boolean isSafeZipEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            return false;
        }
        for (String part : normalized.split("/")) {
            if (part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }

        byte[] buffer = new byte[256 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }

        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    static String resolveDownloadUrl(String source, String directUrl, String officialUrl) {
        if ("github".equals(source)) {
            if (directUrl == null || directUrl.isBlank()) {
                throw new IllegalArgumentException("Github 分片缺少下载地址");
            }
            return directUrl;
        }
        return officialUrl;
    }

    static void ensureDirectory(File directory) throws IOException {
        if (directory == null) {
            return;
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建目录：" + directory.getAbsolutePath());
        }
    }

    static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("无法删除文件：" + file.getAbsolutePath());
        }
    }

    static void deleteRecursively(File file) {
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
}
