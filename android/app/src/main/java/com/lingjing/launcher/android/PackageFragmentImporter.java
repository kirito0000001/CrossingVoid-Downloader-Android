package com.lingjing.launcher.android;

import android.content.ContentResolver;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把玩家从网盘 / QQ 群拿到的碎片导进工作目录。
 *
 * 碎片有三种形态（AxTools《生成碎片》产出的）：
 *   · 分片  `CrossingVoid/Content/Paks/*.pak|ucas|utoc` —— 散件，保持目录结构
 *   · 影片  `CrossingVoid/Content/Movies/<名字>.mp4.zip` —— 每个影片单独一个包
 *   · 其余  `其余文件.zip` —— 一个包，内部保持相对路径
 *
 * **压缩包里的条目名就是清单里的相对路径**，所以这里既不需要知道包叫什么、
 * 也不需要知道哪个包装了哪些文件 —— 拆开按路径对清单就够了。
 * 玩家把 `Login_1.mp4.zip` 改名成 `影片1.zip` 照样能导（条目名没动）。
 *
 * 判定和下载共用同一把尺子：目标文件的 size + sha256 对得上清单就算"已就位"。
 * 于是导入是幂等的 —— 导到一半中断、再点一次不会白拷已经对上的东西。
 *
 * 落地位置不在这里决定：调用方通过 {@link Host#targetFor} 说清每个清单条目该去哪
 * （APK → prepared、OBB 条目 → 条目树下、其余 → prepared 平铺），
 * 这样和下载那条链路的落点天然一致，组装 OBB 那段也就能原样复用。
 */
final class PackageFragmentImporter {

    interface Host {
        /** 响应暂停 / 取消。实现里该抛就抛，本类不自己造控制流。 */
        void checkControlSignals() throws Exception;

        /** 这个清单条目该落到哪个文件。 */
        File targetFor(GamePackagePlan.FileEntry entry);

        /** 一个条目处理完了。index 是已导入数，total 是清单总数。 */
        void publishProgress(String message, int index, int total, boolean force);
    }

    /** 导入结果：对上了几个、缺哪些、哪些内容对不上。 */
    static final class Result {
        int imported;
        final List<String> missing = new ArrayList<>();
        final List<String> mismatched = new ArrayList<>();

        boolean isComplete() {
            return missing.isEmpty() && mismatched.isEmpty();
        }
    }

    private enum Outcome { MISS, IMPORTED, MISMATCHED }

    private final ContentResolver resolver;

    PackageFragmentImporter(ContentResolver resolver) {
        this.resolver = resolver;
    }

    Result importFrom(DocumentFile root, GamePackagePlan plan, Host host) throws Exception {
        Map<String, GamePackagePlan.FileEntry> manifest = new HashMap<>();
        for (GamePackagePlan.FileEntry entry : plan.manifestFiles) {
            manifest.put(normalizePath(entry.path), entry);
        }

        List<DocumentFile> archives = new ArrayList<>();
        Map<String, DocumentFile> loose = new HashMap<>();
        scanLooseFiles(root, "", archives, loose, host);

        Set<String> handled = new HashSet<>();
        Result result = new Result();

        // 散件先来：路径直接对上清单的最省事，也不用开压缩包
        for (Map.Entry<String, DocumentFile> item : loose.entrySet()) {
            host.checkControlSignals();
            GamePackagePlan.FileEntry entry = manifest.get(item.getKey());
            if (entry == null || handled.contains(item.getKey())) continue;
            if (importDocument(item.getValue(), entry, host) == Outcome.IMPORTED) {
                handled.add(item.getKey());
                result.imported++;
                host.publishProgress("已导入 " + result.imported + " 个文件", result.imported,
                    manifest.size(), false);
            }
        }

        // 压缩包：一个包只从头顺序读一遍，命中清单条目就写出去
        for (DocumentFile archive : archives) {
            host.checkControlSignals();
            importArchive(archive, manifest, handled, result, host);
        }

        for (Map.Entry<String, GamePackagePlan.FileEntry> item : manifest.entrySet()) {
            if (handled.contains(item.getKey())) continue;
            if (isAlreadyThere(host.targetFor(item.getValue()), item.getValue())) continue;
            result.missing.add(item.getValue().path);
        }
        return result;
    }

    private void scanLooseFiles(
        DocumentFile directory,
        String prefix,
        List<DocumentFile> archives,
        Map<String, DocumentFile> loose,
        Host host
    ) throws Exception {
        DocumentFile[] children = directory.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            host.checkControlSignals();
            if (child.isDirectory()) {
                scanLooseFiles(child, prefix + child.getName() + "/", archives, loose, host);
                continue;
            }
            if (!child.isFile()) continue;
            String name = child.getName();
            if (name == null || name.isEmpty()) continue;
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                archives.add(child);
                continue;
            }
            loose.put(normalizePath(prefix + name), child);
        }
    }

    private void importArchive(
        DocumentFile archive,
        Map<String, GamePackagePlan.FileEntry> manifest,
        Set<String> handled,
        Result result,
        Host host
    ) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(
            new BufferedInputStream(openDocument(archive), DownloadFileUtils.BUFFER_SIZE))) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                host.checkControlSignals();
                if (item.isDirectory()) continue;
                String key = normalizePath(item.getName());
                if (key.isEmpty()) continue;
                GamePackagePlan.FileEntry entry = manifest.get(key);
                if (entry == null || handled.contains(key)) continue;

                File target = host.targetFor(entry);
                if (isAlreadyThere(target, entry)) {
                    // 已经对上了：不重写，直接算进度（同一个包里重复出现也不会做两遍）
                    handled.add(key);
                    result.imported++;
                    continue;
                }

                File temporary = temporaryFor(target);
                DownloadFileUtils.deleteFile(temporary);
                if (temporary.getParentFile() != null) {
                    DownloadFileUtils.ensureDirectory(temporary.getParentFile());
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
                    copyWithDigest(zip, output, digest, host);
                    output.flush();
                }

                if (matches(entry, temporary.length(), digest)) {
                    DownloadFileUtils.deleteFile(target);
                    if (!temporary.renameTo(target)) {
                        DownloadFileUtils.deleteFile(temporary);
                        throw new IOException("无法保存碎片：" + entry.path);
                    }
                    handled.add(key);
                    result.imported++;
                    host.publishProgress("已导入 " + result.imported + " 个文件", result.imported,
                        manifest.size(), false);
                } else {
                    DownloadFileUtils.deleteFile(temporary);
                    result.mismatched.add(entry.path);
                }
            }
        } catch (IOException error) {
            // 这个 catch 兜的是"处理这个包"过程中的 IO 问题 —— 除了打不开/读断，
            // 也可能是里面写目标文件失败。所以消息别把话说死成"读不了"，
            // 带上原始原因更接近现场。
            throw new IOException("处理碎片压缩包失败：" + archive.getName() + "：" + error.getMessage(), error);
        }
    }

    private Outcome importDocument(
        DocumentFile source,
        GamePackagePlan.FileEntry entry,
        Host host
    ) throws Exception {
        File target = host.targetFor(entry);
        if (isAlreadyThere(target, entry)) return Outcome.IMPORTED;

        host.checkControlSignals();
        File temporary = temporaryFor(target);
        DownloadFileUtils.deleteFile(temporary);
        if (temporary.getParentFile() != null) {
            DownloadFileUtils.ensureDirectory(temporary.getParentFile());
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = openDocument(source);
             OutputStream output = new BufferedOutputStream(
                 new FileOutputStream(temporary), DownloadFileUtils.BUFFER_SIZE)) {
            copyWithDigest(input, output, digest, host);
            output.flush();
        }

        if (!matches(entry, temporary.length(), digest)) {
            DownloadFileUtils.deleteFile(temporary);
            return Outcome.MISMATCHED;
        }
        DownloadFileUtils.deleteFile(target);
        if (!temporary.renameTo(target)) {
            DownloadFileUtils.deleteFile(temporary);
            throw new IOException("无法保存碎片：" + entry.path);
        }
        return Outcome.IMPORTED;
    }

    /** 边拷边算哈希：大文件不能先存下来再读一遍。 */
    private static void copyWithDigest(
        InputStream input,
        OutputStream output,
        MessageDigest digest,
        Host host
    ) throws Exception {
        byte[] buffer = new byte[DownloadFileUtils.BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            host.checkControlSignals();
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
        }
    }

    private InputStream openDocument(DocumentFile file) throws IOException {
        InputStream input = resolver.openInputStream(file.getUri());
        if (input == null) throw new IOException("无法读取碎片文件：" + file.getName());
        return input;
    }

    /** 目标位置已经是正确的那一份（size + sha256 都对得上）。 */
    private static boolean isAlreadyThere(File target, GamePackagePlan.FileEntry entry) throws IOException {
        return target.isFile()
            && target.length() == entry.sizeBytes
            && DownloadFileUtils.hashMatches(target, entry.sha256);
    }

    private static boolean matches(GamePackagePlan.FileEntry entry, long sizeBytes, MessageDigest digest) {
        if (sizeBytes != entry.sizeBytes) return false;
        return toHex(digest.digest()).equalsIgnoreCase(entry.sha256);
    }

    private static File temporaryFor(File target) {
        return new File(target.getParentFile(), target.getName() + ".importing");
    }

    /** 清单和压缩包条目名统一成同一套写法：`\` → `/`、去掉开头的 `./` 和 `/`。 */
    private static String normalizePath(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
