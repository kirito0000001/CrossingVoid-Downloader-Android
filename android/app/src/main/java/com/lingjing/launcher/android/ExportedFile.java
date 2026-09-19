package com.lingjing.launcher.android;

import org.json.JSONException;
import org.json.JSONObject;

/** 导出的一个文件：文件名、大小与 SHA-256。 */
final class ExportedFile {
        final String fileName;
        final long sizeBytes;
        final String sha256;

        ExportedFile(String fileName, long sizeBytes, String sha256) {
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("fileName", fileName);
            result.put("sizeBytes", sizeBytes);
            result.put("sha256", sha256);
            return result;
        }
    }
