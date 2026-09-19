package com.lingjing.launcher.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DownloadFileUtilsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reservesEnoughSpaceForMergeAndExtraction() {
        assertEquals(4_563_402_752L, DownloadFileUtils.requiredFreeBytes(2_147_483_648L));
    }

    @Test
    public void rejectsZipEntriesThatEscapeTheOutputDirectory() {
        assertTrue(DownloadFileUtils.isSafeZipEntry("Android/obb/game/main.1.game.obb"));
        assertFalse(DownloadFileUtils.isSafeZipEntry("../outside.obb"));
        assertFalse(DownloadFileUtils.isSafeZipEntry("Android/../../outside.obb"));
        assertFalse(DownloadFileUtils.isSafeZipEntry("C:/outside.obb"));
        assertFalse(DownloadFileUtils.isSafeZipEntry("/outside.obb"));
    }

    @Test
    public void calculatesLowercaseSha256() throws Exception {
        File file = temporaryFolder.newFile("sample.bin");
        Files.write(file.toPath(), "CrossingVoid".getBytes(StandardCharsets.UTF_8));

        assertEquals(
            "531a8c3ed80d2ae3a154ee40dc8400b4b408f232b6567dcf9b4af6515d57fe8c",
            DownloadFileUtils.sha256(file)
        );
    }

    @Test
    public void requiresDirectUrlOnlyForGithubChunks() {
        assertEquals(
            "https://github.com/example/chunk.part001",
            DownloadFileUtils.resolveDownloadUrl(
                "github",
                "https://github.com/example/chunk.part001",
                "https://oss.example/signed"
            )
        );
        assertEquals(
            "https://oss.example/signed",
            DownloadFileUtils.resolveDownloadUrl("official", "", "https://oss.example/signed")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsGithubChunkWithoutDirectUrl() {
        DownloadFileUtils.resolveDownloadUrl("github", "", "https://oss.example/signed");
    }

    @Test
    public void createsNestedDirectoriesAndIgnoresNull() throws Exception {
        File nested = new File(temporaryFolder.getRoot(), "a/b/c");

        DownloadFileUtils.ensureDirectory(nested);
        DownloadFileUtils.ensureDirectory(null);

        assertTrue(nested.isDirectory());
    }

    @Test
    public void deletesExistingFileAndIgnoresMissingOne() throws Exception {
        File file = temporaryFolder.newFile("obsolete.bin");

        DownloadFileUtils.deleteFile(file);
        DownloadFileUtils.deleteFile(new File(temporaryFolder.getRoot(), "already-gone.bin"));

        assertFalse(file.exists());
    }

    @Test
    public void removesDirectoryTreeRecursivelyAndIgnoresNull() throws Exception {
        File root = new File(temporaryFolder.getRoot(), "work");
        File child = new File(root, "chunks");
        assertTrue(child.mkdirs());
        Files.write(new File(child, "part001").toPath(), new byte[] { 1, 2, 3 });

        DownloadFileUtils.deleteRecursively(root);
        DownloadFileUtils.deleteRecursively(null);

        assertFalse(root.exists());
    }
}
