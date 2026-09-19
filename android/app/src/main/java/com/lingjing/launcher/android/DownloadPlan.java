package com.lingjing.launcher.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 游戏下载清单。
 *
 * 构造时校验产品键、运行平台、下载来源、分片连续性与总大小对账，
 * 以及整包 SHA-256 的格式。清单来自网络，任何一项不成立都直接拒绝。
 */
final class DownloadPlan {
    final String productKey;
    final String runtime;
    final String source;
    final String version;
    final String archiveFileName;
    final String archiveSha256;
    final long totalBytes;
    final List<DownloadChunk> chunks;

    private DownloadPlan(JSONObject source) throws JSONException {
        productKey = source.getString("productKey");
        runtime = source.getString("runtime");
        this.source = source.getString("source");
        version = source.getString("version");
        archiveFileName = source.getString("archiveFileName");
        archiveSha256 = source.getString("archiveSha256").toLowerCase(Locale.ROOT);
        totalBytes = source.getLong("totalBytes");
        chunks = new ArrayList<>();
        JSONArray items = source.getJSONArray("chunks");
        for (int index = 0; index < items.length(); index++) {
            chunks.add(new DownloadChunk(items.getJSONObject(index)));
        }
        chunks.sort(Comparator.comparingInt(chunk -> chunk.index));
        long chunkBytes = 0L;
        for (int position = 0; position < chunks.size(); position++) {
            DownloadChunk chunk = chunks.get(position);
            if (chunk.index != position + 1 || chunk.count != chunks.size()) throw new JSONException("Android 游戏分片序号不连续");
            if (this.source.equals("github") && chunk.downloadUrl.isBlank()) throw new JSONException("Github 游戏分片缺少下载地址");
            chunkBytes = Math.addExact(chunkBytes, chunk.sizeBytes);
        }
        if (!productKey.equals("crossingvoid-android-game") || !runtime.equals("Android")
            || (!this.source.equals("official") && !this.source.equals("github")) || chunks.isEmpty()
            || totalBytes <= 0 || chunkBytes != totalBytes || !archiveSha256.matches("^[a-f0-9]{64}$")) {
            throw new JSONException("下载清单不完整");
        }
    }

    static DownloadPlan parse(String json) throws JSONException {
        return new DownloadPlan(new JSONObject(json));
    }

    boolean matchesState(JSONObject state) {
        return version.equals(state.optString("version")) && archiveSha256.equalsIgnoreCase(state.optString("archiveSha256"));
    }
}
