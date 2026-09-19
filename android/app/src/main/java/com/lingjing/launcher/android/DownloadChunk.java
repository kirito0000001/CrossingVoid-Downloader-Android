package com.lingjing.launcher.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 游戏分片清单里的一条分片。
 *
 * 构造时就校验：序号、总数、文件名格式、对象键、SHA-256 与大小。
 * 清单是外部输入，越早失败越好，不要让坏数据流到下载流程里。
 */
final class DownloadChunk {
    final int index;
    final int count;
    final String fileName;
    final String objectKey;
    final String sha256;
    final long sizeBytes;
    final String downloadUrl;

    DownloadChunk(JSONObject source) throws JSONException {
        index = source.getInt("index");
        count = source.getInt("count");
        fileName = source.getString("fileName");
        objectKey = source.getString("objectKey");
        sha256 = source.getString("sha256");
        sizeBytes = source.getLong("sizeBytes");
        downloadUrl = source.optString("downloadUrl", "");
        String expectedName = String.format(Locale.ROOT, "CrossingVoid手机端.碎片%03d", index);
        if (index <= 0 || count <= 0 || !fileName.equals(expectedName) || objectKey.isBlank()
            || !sha256.matches("^[a-fA-F0-9]{64}$") || sizeBytes <= 0) {
            throw new JSONException("Android 游戏分片清单无效：" + expectedName);
        }
    }
}
