package com.lingjing.launcher.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 清单 v1 的下载计划是外部输入，直接决定会不会把用户带进错误的目录。
 * 这里逐条钉住校验边界。
 */
public class GamePackagePlanTest {
    private static final String SHA = "a".repeat(64);
    private static final String APK = "CrossingVoid-Android-Shipping-arm64.apk";
    private static final String PAK = "CrossingVoid/Content/Paks/pakchunk0-Android.ucas";
    private static final String ENGINE = "Engine/Content/Renderer/TessellationTable.bin";
    private static final String SIDECAR = GamePackagePlan.OBB_SIDECAR_PATH;

    private static JSONObject file(String path, String kind) throws JSONException {
        JSONObject file = new JSONObject();
        file.put("path", path);
        file.put("url", "https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/" + path);
        file.put("sha256", SHA);
        file.put("sizeBytes", 128);
        file.put("kind", kind);
        return file;
    }

    private static JSONObject plan() throws JSONException {
        JSONArray files = new JSONArray();
        files.put(file(APK, GamePackagePlan.KIND_APK));
        files.put(file(PAK, GamePackagePlan.KIND_OBB_ENTRY));
        files.put(file(ENGINE, GamePackagePlan.KIND_OBB_ENTRY));
        files.put(file(SIDECAR, GamePackagePlan.KIND_OBB_SIDECAR));

        JSONObject obb = new JSONObject();
        obb.put("obbFileName", "main.1.com.TFAC.CorssingVoid.obb");
        obb.put("entries", new JSONArray().put(PAK).put(ENGINE));

        JSONObject plan = new JSONObject();
        plan.put("productKey", GamePackagePlan.PRODUCT_KEY);
        plan.put("runtime", GamePackagePlan.RUNTIME);
        plan.put("source", "official");
        plan.put("version", "0.5.14");
        plan.put("baseUrl", "https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/");
        plan.put("totalBytes", 512);
        plan.put("files", files);
        plan.put("obb", obb);
        plan.put("needsObbRebuild", true);
        return plan;
    }

    @Test
    public void parsesThePlanTheLauncherFrontendBuilds() throws Exception {
        GamePackagePlan parsed = GamePackagePlan.parse(plan().toString());
        assertEquals("0.5.14", parsed.version);
        assertEquals(4, parsed.files.size());
        assertEquals(2, parsed.obbEntries.size());
        assertTrue(parsed.needsObbRebuild);
        assertNotNull(parsed.apkEntry());
        assertEquals(APK, parsed.apkEntry().path);
        assertEquals("main.1.com.TFAC.CorssingVoid.obb", parsed.obbFileName);
    }

    @Test
    public void normalizesBaseUrlAndRejectsUnsafePaths() throws Exception {
        assertEquals(
            "https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/",
            GamePackagePlan.normalizeBaseUrl("https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14"));

        JSONObject broken = plan();
        JSONArray files = broken.getJSONArray("files");
        files.getJSONObject(1).put("path", "../escape.ucas");
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(broken.toString()));

        JSONObject absolute = plan();
        absolute.getJSONArray("files").getJSONObject(1).put("path", "/etc/passwd");
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(absolute.toString()));
    }

    @Test
    public void readsCandidateUrlsAndFallsBackToTheSingleUrlField() throws Exception {
        JSONObject plan = plan();
        JSONArray files = plan.getJSONArray("files");
        JSONArray urls = new JSONArray();
        urls.put("https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/" + APK);
        urls.put("https://github.com/kirito0000001/CrossingVoid/releases/download/Android-V0.5.14/" + APK);
        urls.put("ftp://ignored.example.com/a.apk");
        files.getJSONObject(0).put("urls", urls);

        GamePackagePlan parsed = GamePackagePlan.parse(plan.toString());
        GamePackagePlan.FileEntry apk = parsed.apkEntry();
        assertEquals(2, apk.urls.size());
        assertEquals("https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/" + APK, apk.url);
        assertEquals(
            "https://github.com/kirito0000001/CrossingVoid/releases/download/Android-V0.5.14/" + APK,
            apk.urls.get(1));

        // 老的只有 url 字段的清单照样能跑。
        GamePackagePlan legacy = GamePackagePlan.parse(plan().toString());
        assertEquals(1, legacy.apkEntry().urls.size());
        assertEquals(legacy.apkEntry().urls.get(0), legacy.apkEntry().url);
    }

    @Test
    public void rejectsBrokenHashesSizesAndProductKeys() throws Exception {
        JSONObject badHash = plan();
        badHash.getJSONArray("files").getJSONObject(0).put("sha256", "not-a-hash");
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(badHash.toString()));

        JSONObject badSize = plan();
        badSize.getJSONArray("files").getJSONObject(0).put("sizeBytes", 0);
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(badSize.toString()));

        JSONObject badKey = plan();
        badKey.put("productKey", "crossingvoid-game");
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(badKey.toString()));

        JSONObject badUrl = plan();
        badUrl.getJSONArray("files").getJSONObject(0).put("url", "ftp://example.com/a.apk");
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(badUrl.toString()));
    }

    @Test
    public void requiresEveryObbEntryToBeInTheDownloadList() throws Exception {
        JSONObject broken = plan();
        broken.getJSONObject("obb").put("entries", new JSONArray().put("CrossingVoid/Content/Paks/ghost.ucas"));
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(broken.toString()));

        JSONObject badName = plan();
        badName.getJSONObject("obb").put("obbFileName", "CrossingVoid.obb");
        assertThrows(JSONException.class, () -> GamePackagePlan.parse(badName.toString()));
    }

    @Test
    public void toleratesAMissingSidecarForNonObbPackages() throws Exception {
        JSONObject withoutObb = plan();
        withoutObb.remove("obb");
        GamePackagePlan parsed = GamePackagePlan.parse(withoutObb.toString());
        assertNull(parsed.obbEntries.isEmpty() ? null : parsed.obbEntries);
        assertEquals("", parsed.obbFileName);
    }

    @Test
    public void matchesOnlyTheSameVersionAndProduct() throws Exception {
        GamePackagePlan parsed = GamePackagePlan.parse(plan().toString());
        JSONObject state = new JSONObject().put("version", "0.5.14").put("productKey", GamePackagePlan.PRODUCT_KEY);
        assertTrue(parsed.matchesState(state));
        assertTrue(!parsed.matchesState(new JSONObject().put("version", "0.5.13").put("productKey", GamePackagePlan.PRODUCT_KEY)));
    }
}
