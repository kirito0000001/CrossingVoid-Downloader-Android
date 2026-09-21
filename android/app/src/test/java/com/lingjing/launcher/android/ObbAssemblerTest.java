package com.lingjing.launcher.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 组装 OBB 的三条硬规则：
 * 1. 下载过的条目用下载的文件；
 * 2. 没下载的条目从旧 OBB 里搬（否则每次更新都要重下 2 GB）；
 * 3. 两边都没有 → 报错，且不留半成品。
 */
public class ObbAssemblerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final ObbAssembler.Host SILENT_HOST = new ObbAssembler.Host() {
        @Override
        public void publishProgress(String message, boolean force) {
            // 测试里不需要进度。
        }

        @Override
        public void checkControlSignals() {
            // 测试里不暂停、不取消。
        }
    };

    private static final String PAK = "CrossingVoid/Content/Paks/pakchunk0-Android.ucas";
    private static final String ENGINE = "Engine/Content/Renderer/TessellationTable.bin";
    private static final String MOVIE = "CrossingVoid/Content/Movies/Login_1.mp4";

    private File writeFile(String name, String content) throws IOException {
        File file = new File(temporaryFolder.getRoot(), name);
        DownloadFileUtils.ensureDirectory(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private File createZip(String name, Map<String, String> entries) throws IOException {
        File file = new File(temporaryFolder.getRoot(), name);
        DownloadFileUtils.ensureDirectory(file.getParentFile());
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.setLevel(Deflater.NO_COMPRESSION);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return file;
    }

    private static String readEntry(File zipFile, String entryName) throws IOException {
        try (ZipFile archive = new ZipFile(zipFile)) {
            ZipEntry entry = archive.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream input = archive.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }

    /** 按物理顺序读条目名（用来看是否跟旁车顺序一致）。 */
    private static List<String> physicalEntryOrder(File zipFile) throws IOException {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(zipFile); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            bytes = output.toByteArray();
        }
        List<String> names = new ArrayList<>();
        for (int index = 0; index + 30 < bytes.length; index++) {
            boolean signature =
                bytes[index] == 'P' && bytes[index + 1] == 'K' && bytes[index + 2] == 3 && bytes[index + 3] == 4;
            if (!signature) continue;
            // 本地文件头：签名(4) 版本(2) 标志(2) 压缩方式(2) 时间(2) 日期(2) CRC(4)
            //            压缩后大小(4) 原始大小(4) 文件名长度(2) 扩展长度(2) 文件名…
            int nameLength = (bytes[index + 26] & 0xff) | ((bytes[index + 27] & 0xff) << 8);
            int extraLength = (bytes[index + 28] & 0xff) | ((bytes[index + 29] & 0xff) << 8);
            if (nameLength <= 0 || index + 30 + nameLength > bytes.length) continue;
            names.add(new String(bytes, index + 30, nameLength, StandardCharsets.UTF_8));
            index += 30 + nameLength + extraLength - 1;
        }
        return names;
    }

    @Test
    public void assemblesAFreshObbFromDownloadedEntries() throws Exception {
        File pak = writeFile("downloads/x.ucas", "pak-bytes");
        File engine = writeFile("downloads/y.bin", "engine-bytes");
        Map<String, File> staged = new HashMap<>();
        staged.put(PAK, pak);
        staged.put(ENGINE, engine);

        File output = new File(temporaryFolder.getRoot(), "obb/main.1.com.TFAC.CorssingVoid.obb");
        new ObbAssembler(SILENT_HOST).assemble(output, List.of(PAK, ENGINE), staged, null);

        assertTrue(output.isFile());
        assertEquals("pak-bytes", readEntry(output, PAK));
        assertEquals("engine-bytes", readEntry(output, ENGINE));
        assertEquals(List.of(PAK, ENGINE), physicalEntryOrder(output));
        assertFalse(new File(output.getParentFile(), output.getName() + ".assembling").exists());
    }

    @Test
    public void reusesUnchangedEntriesFromTheInstalledObb() throws Exception {
        Map<String, String> previousEntries = new LinkedHashMap<>();
        previousEntries.put(PAK, "old-pak");
        previousEntries.put(ENGINE, "engine");
        File existing = createZip("obb/main.1.com.TFAC.CorssingVoid.obb", previousEntries);

        // 这次只下了一个变了的内容包条目。
        File changed = writeFile("downloads/new.pak", "new-pak");
        Map<String, File> staged = new HashMap<>();
        staged.put(PAK, changed);

        File output = new File(temporaryFolder.getRoot(), "obb/main.2.com.TFAC.CorssingVoid.obb");
        new ObbAssembler(SILENT_HOST).assemble(output, List.of(PAK, ENGINE), staged, existing);

        assertEquals("new-pak", readEntry(output, PAK));
        assertEquals("engine", readEntry(output, ENGINE));
    }

    @Test
    public void failsLoudlyWhenAnEntryIsNeitherDownloadedNorInTheOldObb() throws Exception {
        File existing = createZip(
            "obb/main.1.com.TFAC.CorssingVoid.obb",
            Map.of(PAK, "old-pak"));
        File output = new File(temporaryFolder.getRoot(), "obb/main.3.com.TFAC.CorssingVoid.obb");

        IOException error = assertThrows(
            IOException.class,
            () -> new ObbAssembler(SILENT_HOST)
                .assemble(output, List.of(PAK, MOVIE), new HashMap<>(), existing));

        assertTrue(error.getMessage().contains(MOVIE));
        assertFalse("失败不应该留下半个 OBB", output.exists());
        assertFalse(
            "失败不应该留下临时文件",
            new File(output.getParentFile(), output.getName() + ".assembling").exists());
    }

    @Test
    public void listsExistingEntriesWithSizes() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(PAK, "12345");
        entries.put(ENGINE, "1234567");
        File existing = createZip("obb/main.1.com.TFAC.CorssingVoid.obb", entries);

        Map<String, Long> listed = ObbAssembler.listEntries(existing);
        assertEquals(2, listed.size());
        assertEquals(Long.valueOf(5), listed.get(PAK));
        assertEquals(Long.valueOf(7), listed.get(ENGINE));
    }

    @Test
    public void assembledEntriesAreStoredNotCompressed() throws Exception {
        File pak = writeFile("downloads/plain.bin", "x".repeat(1000));
        File output = new File(temporaryFolder.getRoot(), "obb/main.4.com.TFAC.CorssingVoid.obb");
        new ObbAssembler(SILENT_HOST).assemble(output, List.of(PAK), Map.of(PAK, pak), null);

        try (ZipFile archive = new ZipFile(output)) {
            ZipEntry entry = archive.getEntry(PAK);
            assertEquals(ZipEntry.STORED, entry.getMethod());
            assertArrayEquals(
                "x".repeat(1000).getBytes(StandardCharsets.UTF_8),
                readEntryBytes(output, PAK));
        }
    }

    private static byte[] readEntryBytes(File zipFile, String entryName) throws IOException {
        try (ZipFile archive = new ZipFile(zipFile)) {
            ZipEntry entry = archive.getEntry(entryName);
            try (InputStream input = archive.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
                return output.toByteArray();
            }
        }
    }
}
