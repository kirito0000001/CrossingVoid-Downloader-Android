package com.lingjing.launcher.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

/**
 * 下载清单是外部输入，直接决定用户会不会下到错误的包。
 * 这里逐条钉住 Plan/Chunk 的校验边界。
 */
public class DownloadPlanTest {
    private static final String ARCHIVE_SHA = "a".repeat(64);
    private static final String CHUNK_SHA = "b".repeat(64);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static JSONObject chunk(int index, int count, long sizeBytes) throws JSONException {
        JSONObject chunk = new JSONObject();
        chunk.put("index", index);
        chunk.put("count", count);
        chunk.put("fileName", String.format(Locale.ROOT, "CrossingVoid手机端.碎片%03d", index));
        chunk.put("objectKey", "game/android/chunk-" + index);
        chunk.put("sha256", CHUNK_SHA);
        chunk.put("sizeBytes", sizeBytes);
        return chunk;
    }

    private static JSONObject manifest() throws JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("productKey", "crossingvoid-android-game");
        manifest.put("runtime", "Android");
        manifest.put("source", "official");
        manifest.put("version", "V0.5.12");
        manifest.put("archiveFileName", "CrossingVoidAndroid.zip");
        manifest.put("archiveSha256", ARCHIVE_SHA);
        manifest.put("totalBytes", 3000L);
        JSONArray chunks = new JSONArray();
        chunks.put(chunk(1, 2, 1000L));
        chunks.put(chunk(2, 2, 2000L));
        manifest.put("chunks", chunks);
        return manifest;
    }

    private static DownloadPlan parse(JSONObject manifest) throws JSONException {
        return DownloadPlan.parse(manifest.toString());
    }

    @Test
    public void parsesOfficialManifest() throws Exception {
        DownloadPlan plan = parse(manifest());

        assertEquals("crossingvoid-android-game", plan.productKey);
        assertEquals("Android", plan.runtime);
        assertEquals("official", plan.source);
        assertEquals("V0.5.12", plan.version);
        assertEquals("CrossingVoidAndroid.zip", plan.archiveFileName);
        assertEquals(3000L, plan.totalBytes);
        assertEquals(2, plan.chunks.size());
        assertEquals(1, plan.chunks.get(0).index);
        assertEquals(2, plan.chunks.get(1).index);
    }

    @Test
    public void sortsChunksByIndex() throws Exception {
        JSONObject manifest = manifest();
        JSONArray reversed = new JSONArray();
        reversed.put(chunk(2, 2, 2000L));
        reversed.put(chunk(1, 2, 1000L));
        manifest.put("chunks", reversed);

        DownloadPlan plan = parse(manifest);

        assertEquals(1, plan.chunks.get(0).index);
        assertEquals(2, plan.chunks.get(1).index);
    }

    @Test
    public void acceptsGithubManifestWithDirectUrls() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("source", "github");
        JSONArray chunks = manifest.getJSONArray("chunks");
        for (int index = 0; index < chunks.length(); index++) {
            chunks.getJSONObject(index).put("downloadUrl", "https://github.com/example/" + index);
        }

        assertEquals("github", parse(manifest).source);
    }

    @Test
    public void normalizesUppercaseArchiveHash() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("archiveSha256", "A".repeat(64));

        assertEquals("a".repeat(64), parse(manifest).archiveSha256);
    }

    @Test(expected = JSONException.class)
    public void rejectsForeignProductKey() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("productKey", "crossingvoid-game");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsNonAndroidRuntime() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("runtime", "Windows");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsUnknownSource() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("source", "mirror");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsEmptyChunkList() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("chunks", new JSONArray());
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsNonContiguousChunkIndexes() throws Exception {
        JSONObject manifest = manifest();
        JSONArray chunks = new JSONArray();
        chunks.put(chunk(1, 2, 1000L));
        chunks.put(chunk(3, 2, 2000L));
        manifest.put("chunks", chunks);
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsChunkCountMismatch() throws Exception {
        JSONObject manifest = manifest();
        JSONArray chunks = new JSONArray();
        chunks.put(chunk(1, 3, 1000L));
        chunks.put(chunk(2, 3, 2000L));
        manifest.put("chunks", chunks);
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsGithubChunkWithoutDirectUrl() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("source", "github");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsTotalBytesMismatch() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("totalBytes", 3001L);
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsMalformedArchiveHash() throws Exception {
        JSONObject manifest = manifest();
        manifest.put("archiveSha256", "not-a-hash");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsChunkWithUnexpectedFileName() throws Exception {
        JSONObject manifest = manifest();
        manifest.getJSONArray("chunks").getJSONObject(0).put("fileName", "CrossingVoid手机端.碎片99");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsChunkWithMalformedHash() throws Exception {
        JSONObject manifest = manifest();
        manifest.getJSONArray("chunks").getJSONObject(0).put("sha256", "short");
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsChunkWithNonPositiveSize() throws Exception {
        JSONObject manifest = manifest();
        JSONArray chunks = new JSONArray();
        chunks.put(chunk(1, 2, 0L));
        chunks.put(chunk(2, 2, 3000L));
        manifest.put("chunks", chunks);
        parse(manifest);
    }

    @Test(expected = JSONException.class)
    public void rejectsChunkWithNonPositiveIndex() throws Exception {
        JSONObject manifest = manifest();
        JSONArray chunks = new JSONArray();
        chunks.put(chunk(0, 1, 3000L));
        manifest.put("chunks", chunks);
        parse(manifest);
    }

    @Test
    public void matchesStateByVersionAndArchiveHash() throws Exception {
        DownloadPlan plan = parse(manifest());

        JSONObject same = new JSONObject();
        same.put("version", "V0.5.12");
        same.put("archiveSha256", ARCHIVE_SHA.toUpperCase(Locale.ROOT));
        assertTrue(plan.matchesState(same));

        same.put("version", "V0.5.13");
        assertFalse(plan.matchesState(same));
    }

    @Test
    public void countsOnlyBytesActuallyPresentOnDisk() throws Exception {
        DownloadPlan plan = parse(manifest());
        File chunksDir = temporaryFolder.newFolder("chunks");
        Files.write(new File(chunksDir, "CrossingVoid手机端.碎片001").toPath(), new byte[1000]);
        Files.write(new File(chunksDir, "CrossingVoid手机端.碎片002").toPath(), new byte[400]);

        assertEquals(1400L, DownloadFileUtils.existingChunkBytes(plan, chunksDir));
    }
}
