package com.lingjing.launcher.android;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把下载好的 OBB 条目组装成一个新的 OBB（STORED 不压缩 ZIP）。
 *
 * ## 为什么要"搬旧的"而不是只用下载好的文件
 *
 * 更新时只下 sha256 变了的那几个条目，**没变的条目本地并没有一份散文件**——
 * 它们只在已安装的那个 OBB 里。如果组装时只认下载目录，每次更新都得先把
 * 2 GB 的旧条目重新下一遍。规则因此是：
 *
 * - 该条目这次下载了 → 用下载目录里的那份；
 * - 没下载 → 从**现有 OBB** 里按同名条目原样搬过来；
 * - 两边都没有 → 报错并点名条目（宁可失败，也不要组装出一个缺文件的 OBB）。
 *
 * UE 只按 ZIP 条目读，所以组装结果不要求和原始 OBB 逐字节一致。
 */
final class ObbAssembler {

    interface Host {
        void publishProgress(String message, boolean force);

        void checkControlSignals() throws Exception;
    }

    private static final int BUFFER_SIZE = 1024 * 256;

    private final Host host;

    ObbAssembler(Host host) {
        this.host = host;
    }

    /** 一个可以打开两次的来源（STORED 需要先算 CRC 再拷字节）。 */
    private interface EntrySource {
        long size() throws IOException;

        InputStream open() throws IOException;
    }

    /**
     * @param outputFile  目标 OBB（先写 `.assembling` 再改名，失败不留半成品）
     * @param entries     旁车里的条目顺序
     * @param stagedFiles 这次下载的文件：条目路径 → 本地文件
     * @param existingObb 已安装的 OBB；首次安装可以传 null（这时所有条目都得是下载来的）
     */
    File assemble(File outputFile, List<String> entries, Map<String, File> stagedFiles, File existingObb)
        throws Exception {
        DownloadFileUtils.ensureDirectory(outputFile.getParentFile());
        File temporary = new File(outputFile.getParentFile(), outputFile.getName() + ".assembling");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理临时 OBB：" + temporary.getAbsolutePath());
        }

        ZipFile previous = null;
        try {
            if (existingObb != null && existingObb.isFile() && existingObb.length() > 0) {
                previous = new ZipFile(existingObb);
            }
            final ZipFile previousArchive = previous;

            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temporary))) {
                output.setLevel(Deflater.NO_COMPRESSION);
                // ⚠️ 这一行不能省。UE 在 Java 层靠 **EOCD 末尾的全局注释** 判断"这是不是一个有效的 OBB"，
                // 格式是 `%10d` 的 versionCode —— UE 自己产出的 OBB 尾部长这样：
                //   ... PK\x05\x06 0000 0000 3e00 3e00 <目录偏移> 0a00 "         1"
                // 缺了它 UE 直接弹 "No OBB found and no store key"，**根本不看里面内容对不对**。
                // 2026-09-22 用户实测：缺注释的组装产物报这个错；把带注释的原始 OBB 放上去就被认到了。
                output.setComment(String.format(Locale.ROOT, "%10d", obbVersionFromName(outputFile)));
                for (int index = 0; index < entries.size(); index++) {
                    host.checkControlSignals();
                    String entryPath = entries.get(index);
                    host.publishProgress("正在组装 OBB " + (index + 1) + " / " + entries.size(), false);
                    writeEntry(output, entryPath, resolveSource(entryPath, stagedFiles, previousArchive));
                }
            }
        } catch (Exception error) {
            if (temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            throw error;
        } finally {
            if (previous != null) {
                try {
                    previous.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响主流程。
                }
            }
        }

        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("无法替换旧的 OBB：" + outputFile.getAbsolutePath());
        }
        if (!temporary.renameTo(outputFile)) {
            throw new IOException("无法写入新的 OBB：" + outputFile.getAbsolutePath());
        }
        return outputFile;
    }

    /**
     * OBB 名固定是 `main.<versionCode>.<package>.obb` —— 中间那个数字就是
     * UE 写在 ZIP 全局注释里的值（`%10d` 右对齐）。解析不出来就退回 1。
     */
    private static int obbVersionFromName(File outputFile) {
        Matcher matcher = Pattern.compile("^(?:main|patch)\\.(\\d+)\\.").matcher(outputFile.getName());
        if (!matcher.find()) return 1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return 1;
        }
    }

    /** 已有 OBB 里的条目名 → 未压缩大小，用来判断能不能只搬旧文件。 */
    static Map<String, Long> listEntries(File obbFile) throws IOException {
        Map<String, Long> entries = new HashMap<>();
        if (obbFile == null || !obbFile.isFile()) return entries;
        try (ZipFile archive = new ZipFile(obbFile)) {
            Enumeration<? extends ZipEntry> iterator = archive.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (!entry.isDirectory()) entries.put(entry.getName(), entry.getSize());
            }
        }
        return entries;
    }

    private EntrySource resolveSource(String entryPath, Map<String, File> stagedFiles, ZipFile previous)
        throws IOException {
        File staged = stagedFiles.get(entryPath);
        if (staged != null && staged.isFile()) {
            return fileSource(staged);
        }
        if (previous != null) {
            ZipEntry existing = previous.getEntry(entryPath);
            if (existing != null && !existing.isDirectory()) {
                return archiveSource(previous, existing);
            }
        }
        throw new IOException("OBB 条目既没有下载、也不在旧 OBB 里：" + entryPath);
    }

    private EntrySource fileSource(File file) {
        return new EntrySource() {
            @Override
            public long size() {
                return file.length();
            }

            @Override
            public InputStream open() throws IOException {
                return new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE);
            }
        };
    }

    private EntrySource archiveSource(ZipFile archive, ZipEntry entry) {
        return new EntrySource() {
            @Override
            public long size() {
                return entry.getSize();
            }

            @Override
            public InputStream open() throws IOException {
                return archive.getInputStream(entry);
            }
        };
    }

    /** STORED 条目必须在 putNextEntry 前给出 size 与 crc：来源读两遍。 */
    private void writeEntry(ZipOutputStream output, String entryPath, EntrySource source) throws Exception {
        CRC32 crc = new CRC32();
        long size = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        try (InputStream input = source.open()) {
            while ((read = input.read(buffer)) > 0) {
                host.checkControlSignals();
                crc.update(buffer, 0, read);
                size += read;
            }
        }
        if (size <= 0) {
            long hinted = source.size();
            if (hinted > 0) {
                throw new IOException("OBB 条目读取为空：" + entryPath);
            }
        }

        ZipEntry entry = new ZipEntry(entryPath);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        try (InputStream input = source.open()) {
            while ((read = input.read(buffer)) > 0) {
                host.checkControlSignals();
                output.write(buffer, 0, read);
            }
        }
        output.closeEntry();
    }
}
