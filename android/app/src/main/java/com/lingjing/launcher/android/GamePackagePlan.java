package com.lingjing.launcher.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 清单 v1 的下载计划（与 PC 端同一套字段，由前端 `src/services/gamePackageUpdate.ts` 生成）。
 *
 * 与老的 {@link DownloadPlan}（分片 + 整包 zip）并存：这一版按文件下载，
 * 落位规则见规划文档《GameDownloadRetrofitPlan》第四章。
 *
 * 外部输入一律先校验再使用：路径安全、sha256 格式、大小、URL 协议，
 * 以及 OBB 旁车里的每个条目都必须出现在 files[] 里。
 */
final class GamePackagePlan {
    static final String PRODUCT_KEY = "crossingvoid-android-game";
    static final String RUNTIME = "Android";
    static final String KIND_APK = "apk";
    static final String KIND_OBB_ENTRY = "obb-entry";
    static final String KIND_METADATA = "metadata";
    static final String KIND_OBB_SIDECAR = "obb-sidecar";
    static final String OBB_SIDECAR_PATH = "CrossingVoid.obb.json";

    final String productKey;
    final String runtime;
    final String source;
    final String version;
    final String baseUrl;
    final long totalBytes;
    final List<FileEntry> files;
    /** 清单全量（含没下的），安装成功后写进本地状态，供下次算差异。 */
    final List<FileEntry> manifestFiles;
    final List<String> obbEntries;
    final String obbFileName;
    final boolean needsObbRebuild;
    private final Map<String, FileEntry> byPath = new HashMap<>();

    static final class FileEntry {
        final String path;
        /** 候选下载地址：首选源在前，另一个源兜底（GitHub 用 `路径 → 附件名` 的那份）。 */
        final List<String> urls;
        /** 首选地址，等于 urls[0]。 */
        final String url;
        final String sha256;
        final long sizeBytes;
        final String kind;

        FileEntry(String path, List<String> urls, String sha256, long sizeBytes, String kind) {
            this.path = path;
            this.urls = urls;
            this.url = urls.isEmpty() ? "" : urls.get(0);
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.kind = kind;
        }

        boolean isApk() {
            return KIND_APK.equals(kind);
        }

        boolean isObbEntry() {
            return KIND_OBB_ENTRY.equals(kind);
        }
    }

    private GamePackagePlan(JSONObject source) throws JSONException {
        this.source = source.getString("source");
        productKey = source.getString("productKey");
        runtime = source.getString("runtime");
        version = source.getString("version");
        baseUrl = normalizeBaseUrl(source.getString("baseUrl"));
        totalBytes = source.getLong("totalBytes");
        needsObbRebuild = source.optBoolean("needsObbRebuild", true);

        JSONArray items = source.getJSONArray("files");
        files = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String path = requireSafeRelativePath(item.getString("path"));
            List<String> urls = readCandidateUrls(item, path);
            String sha256 = item.getString("sha256").toLowerCase(Locale.ROOT);
            if (!sha256.matches("^[a-f0-9]{64}$")) throw new JSONException("文件缺少有效 SHA256：" + path);
            long sizeBytes = item.getLong("sizeBytes");
            if (sizeBytes <= 0) throw new JSONException("文件大小无效：" + path);
            files.add(new FileEntry(path, urls, sha256, sizeBytes, item.getString("kind")));
        }

        for (FileEntry entry : files) {
            if (byPath.put(entry.path, entry) != null) {
                throw new JSONException("下载清单里有重复路径：" + entry.path);
            }
        }

        manifestFiles = new ArrayList<>(files);
        JSONArray manifestItems = source.optJSONArray("manifestFiles");
        if (manifestItems != null) {
            manifestFiles.clear();
            Map<String, FileEntry> known = new HashMap<>(byPath);
            for (int index = 0; index < manifestItems.length(); index++) {
                JSONObject item = manifestItems.getJSONObject(index);
                String path = requireSafeRelativePath(item.getString("path"));
                FileEntry downloaded = known.get(path);
                if (downloaded != null) {
                    manifestFiles.add(downloaded);
                    continue;
                }
                String sha256 = item.getString("sha256").toLowerCase(Locale.ROOT);
                long sizeBytes = item.getLong("sizeBytes");
                if (!sha256.matches("^[a-f0-9]{64}$") || sizeBytes <= 0) {
                    throw new JSONException("清单文件记录无效：" + path);
                }
                manifestFiles.add(new FileEntry(path, new ArrayList<>(), sha256, sizeBytes, item.optString("kind", KIND_METADATA)));
            }
        }

        JSONObject obb = source.optJSONObject("obb");
        if (obb == null) {
            obbEntries = new ArrayList<>();
            obbFileName = "";
        } else {
            obbFileName = obb.getString("obbFileName");
            if (!obbFileName.matches("^(?i)(main|patch)\\.\\d+\\.[A-Za-z0-9_.]+\\.obb$")) {
                throw new JSONException("OBB 文件名不符合规范：" + obbFileName);
            }
            JSONArray entries = obb.getJSONArray("entries");
            obbEntries = new ArrayList<>();
            for (int index = 0; index < entries.length(); index++) {
                String entryPath = requireSafeRelativePath(entries.getString(index));
                FileEntry known = byPath.get(entryPath);
                if (known == null || !known.isObbEntry()) {
                    throw new JSONException("OBB 条目不在下载清单里：" + entryPath);
                }
                obbEntries.add(entryPath);
            }
            if (obbEntries.isEmpty()) throw new JSONException("OBB 旁车没有条目");
        }

        if (!productKey.equals(PRODUCT_KEY) || !runtime.equals(RUNTIME)
            || (!this.source.equals("official") && !this.source.equals("github"))
            || files.isEmpty() || totalBytes < 0) {
            throw new JSONException("下载清单不完整");
        }
    }

    static GamePackagePlan parse(String json) throws JSONException {
        return new GamePackagePlan(new JSONObject(json));
    }

    FileEntry apkEntry() {
        for (FileEntry entry : files) {
            if (entry.isApk()) return entry;
        }
        return null;
    }

    /** APK 的 sha256：状态里留个可核对的指纹。 */
    String apkSha256() {
        FileEntry apk = apkEntry();
        return apk == null ? "" : apk.sha256;
    }

    FileEntry fileByPath(String path) {
        return byPath.get(path);
    }

    /** 下载清单是否描述了同一次安装（版本 + 文件名集合一致）。 */
    boolean matchesState(JSONObject state) {
        return version.equals(state.optString("version"))
            && productKey.equals(state.optString("productKey"));
    }

    static String normalizeBaseUrl(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /**
     * 取一个文件的候选下载地址：优先读新的 `urls` 数组（首选源在前），
     * 只有老的 `url` 字段也能跑；非 http(s) 的地址直接丢掉。
     */
    private static List<String> readCandidateUrls(JSONObject item, String path) throws JSONException {
        List<String> urls = new ArrayList<>();
        JSONArray raw = item.optJSONArray("urls");
        if (raw != null) {
            for (int index = 0; index < raw.length(); index++) {
                addCandidateUrl(urls, raw.optString(index, ""));
            }
        }
        if (urls.isEmpty()) {
            addCandidateUrl(urls, item.optString("url", ""));
        }
        if (urls.isEmpty()) {
            throw new JSONException("下载地址协议不受支持：" + path);
        }
        return urls;
    }

    private static void addCandidateUrl(List<String> urls, String raw) {
        String url = raw == null ? "" : raw.trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        if (!urls.contains(url)) urls.add(url);
    }

    /** 清单里的路径必须是安全的相对路径：无盘符、无开头斜杠、无 `..`。 */
    static String requireSafeRelativePath(String raw) throws JSONException {
        String normalized = raw == null ? "" : raw.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new JSONException("下载清单里的路径不安全：" + raw);
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new JSONException("下载清单里的路径不安全：" + raw);
            }
        }
        return normalized;
    }
}
