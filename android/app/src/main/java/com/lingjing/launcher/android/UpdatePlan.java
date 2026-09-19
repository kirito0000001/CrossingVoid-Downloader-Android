package com.lingjing.launcher.android;

import org.json.JSONException;
import org.json.JSONObject;

/** 启动器更新清单：版本号、下载地址与校验信息。 */
final class UpdatePlan {
        final String versionName;
        final long versionCode;
        final String fileName;
        final String url;
        final long sizeBytes;
        final String sha256;

        UpdatePlan(JSONObject source) throws JSONException {
            String productKey = source.getString("productKey");
        if (!LauncherUpdateService.INSTALLER_PRODUCT_KEY.equals(productKey)) {
                throw new JSONException("启动器更新产品标识不正确");
            }
            versionName = source.getString("versionName");
            versionCode = source.getLong("versionCode");
            JSONObject asset = source.getJSONObject("asset");
            fileName = asset.getString("fileName");
            url = asset.getString("url");
            sizeBytes = asset.getLong("sizeBytes");
            sha256 = asset.getString("sha256").toLowerCase();
            if (versionCode <= 0 || sizeBytes <= 0 || sha256.length() != 64 || !url.startsWith("https://")) {
                throw new JSONException("启动器更新清单不完整");
            }
        }

        static UpdatePlan parse(String json) throws JSONException {
            return new UpdatePlan(new JSONObject(json));
        }
    }
