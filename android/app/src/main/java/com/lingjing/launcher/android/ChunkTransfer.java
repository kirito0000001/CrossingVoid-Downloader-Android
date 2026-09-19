package com.lingjing.launcher.android;

import android.content.ContentResolver;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.FileOutputStream;
import org.json.JSONException;
import java.io.BufferedOutputStream;
import java.util.UUID;
import org.json.JSONObject;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.List;
import java.security.MessageDigest;
import java.io.InputStream;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.OutputStream;

/**
 * 通过 SAF 读写用户选择的目录。
 *
 * 只做文件操作，不碰下载状态；后续把导出/导入流程搬进来时，
 * 进度与状态回调会以宿主接口的形式补上。
 */
final class ChunkTransfer {
    interface Host {
        void checkControlSignals() throws Exception;

        void publishState(String status, String message, long downloadedBytes, double percent, int currentChunk, boolean force, PreparedFiles prepared);

        void saveState(JSONObject state);

        void notifyResult(boolean success, String message);

        void cleanStaleObb(File obbDir, File currentObb);
    }

    private static final String RECOVERY_MANIFEST_FILE = "零境启动器恢复信息.json";

    private final ContentResolver resolver;
    private final Host host;

    ChunkTransfer(ContentResolver resolver, Host host) {
        this.resolver = resolver;
        this.host = host;
    }

    void writeDocumentBytes(DocumentFile root, String fileName, byte[] content) throws IOException {
        String temporaryName = fileName + ".exporting";
        deleteDocument(root.findFile(temporaryName));
        DocumentFile temporary = root.createFile("application/json", temporaryName);
        if (temporary == null) throw new IOException("无法创建恢复信息文件。");
        try (OutputStream output = resolver.openOutputStream(temporary.getUri(), "wt")) {
            if (output == null) throw new IOException("无法写入恢复信息文件。");
            output.write(content);
        }
        deleteDocument(root.findFile(fileName));
        if (!temporary.renameTo(fileName)) throw new IOException("无法保存恢复信息文件。");
    }

    void deleteDocument(DocumentFile file) throws IOException {
        if (file != null && file.exists() && !file.delete()) throw new IOException("无法覆盖导出文件：" + file.getName());
    }

    DocumentFile findDocumentByName(DocumentFile directory, String name) {
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

    String sha256(DocumentFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream rawInput = resolver.openInputStream(file.getUri());
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

    String readDocumentText(DocumentFile file) throws IOException {
        if (file.length() <= 0 || file.length() > 64L * 1024L) throw new IOException("恢复信息文件大小异常。");
        try (InputStream input = resolver.openInputStream(file.getUri());
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

    void collectChunkDocuments(DownloadPlan plan, DocumentFile directory, List<DocumentFile> result) {
        for (DocumentFile child : directory.listFiles()) {
            if (child.isDirectory()) {
                collectChunkDocuments(plan, child, result);
            } else if (child.isFile() && findChunkByName(plan, child.getName()) != null) {
                result.add(child);
            }
        }
    }

    DownloadChunk findChunkByName(DownloadPlan plan, String name) {
        if (name == null || !name.matches("^CrossingVoid手机端\\.碎片\\d{3}$")) return null;
        for (DownloadChunk chunk : plan.chunks) {
            if (chunk.fileName.equals(name)) return chunk;
        }
        return null;
    }

    boolean allChunksAvailable(DownloadPlan plan, File chunksDir) throws IOException {
        for (DownloadChunk chunk : plan.chunks) {
            File source = new File(chunksDir, chunk.fileName);
            if (source.length() != chunk.sizeBytes || !DownloadFileUtils.hashMatches(source, chunk.sha256)) return false;
        }
        return true;
    }

    String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    void exportVerifiedChunks(DocumentFile root, DownloadPlan plan, File chunksDir) throws Exception {
        long copiedBefore = 0L;
        for (DownloadChunk chunk : plan.chunks) {
            File source = new File(chunksDir, chunk.fileName);
            copyAndVerifyExportedFile(source, root, chunk.fileName, chunk.sha256, chunk.sizeBytes,
                copiedBefore, plan.totalBytes, chunk.index, chunk.count);
            copiedBefore += chunk.sizeBytes;
        }
    }

    void exportPreparedRecoveryFiles(DownloadPlan plan, DocumentFile root, JSONObject previousState) throws Exception {
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
        manifest.put("version", plan.version);
        manifest.put("archiveSha256", plan.archiveSha256);
        manifest.put("apk", apkExport.toJson());
        manifest.put("obb", obbExport.toJson());
        writeDocumentBytes(root, RECOVERY_MANIFEST_FILE, manifest.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    ExportedFile copyAndVerifyExportedFile(File source, DocumentFile root, String fileName, String expectedSha256,
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
             OutputStream rawOutput = resolver.openOutputStream(tempFile.getUri(), "wt");
             OutputStream output = rawOutput == null ? null : new BufferedOutputStream(rawOutput, DownloadFileUtils.BUFFER_SIZE)) {
            if (output == null) throw new IOException("无法写入导出文件：" + temporaryName);
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                host.checkControlSignals();
                if (read == 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                long processed = copiedBefore + copied;
                host.publishState("exporting", "正在导出第 " + itemIndex + " / " + itemCount + " 项：" + fileName,
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

    void restoreStateAfterExport(JSONObject previousState, boolean success, String message) {
        JSONObject restored;
        try {
            restored = new JSONObject(previousState.toString());
            restored.put("status", success ? "ready" : "error");
            restored.put("message", message);
            restored.put("canPause", false);
            restored.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException error) {
            restored = DownloadStateStore.errorState(message);
        }
        host.saveState(restored);
        host.notifyResult(success, message);
    }

    PreparedFiles importPreparedRecoveryFiles(DocumentFile root, DownloadPlan plan, File downloadsRoot, File obbDir) throws Exception {
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
        host.cleanStaleObb(obbDir, obbDestination);
        return new PreparedFiles(apkDestination, obbDestination, UUID.randomUUID().toString());
    }

    void copyAndVerifyRecoveryDocument(DocumentFile source, File destination, long expectedSize,
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
        try (InputStream rawInput = resolver.openInputStream(source.getUri());
             InputStream input = rawInput == null ? null : new BufferedInputStream(rawInput, DownloadFileUtils.BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
            if (input == null) throw new IOException("无法读取恢复文件：" + source.getName());
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                host.checkControlSignals();
                if (read == 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                long processed = copiedBefore + copied;
                host.publishState("importing", "正在恢复第 " + itemIndex + " / " + itemCount + " 项：" + source.getName(),
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

    void copyAndVerifyImportedChunk(DocumentFile source, DownloadChunk chunk, File destination, File chunksDir, DownloadPlan plan) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".importing");
        DownloadFileUtils.deleteFile(temporary);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        long copied = 0L;
        long existing = DownloadFileUtils.existingChunkBytes(plan, chunksDir);
        try (InputStream sourceInput = resolver.openInputStream(source.getUri());
             InputStream bufferedInput = sourceInput == null ? null : new BufferedInputStream(sourceInput, DownloadFileUtils.BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
            if (bufferedInput == null) throw new IOException("无法读取导入的碎片：" + chunk.fileName);
            byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
            int read;
            while ((read = bufferedInput.read(buffer)) >= 0) {
                host.checkControlSignals();
                if (read <= 0) continue;
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
                long processed = Math.min(plan.totalBytes, existing + copied);
                host.publishState("importing", "正在导入并校验第 " + chunk.index + " / " + chunk.count + " 片", processed,
                    processed / (double) plan.totalBytes * 85.0, chunk.index, true, null);
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
}
