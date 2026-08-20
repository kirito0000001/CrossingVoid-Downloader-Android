import { CapacitorHttp } from "@capacitor/core";

export const ANDROID_GAME_METADATA_MANIFEST_URL =
  "https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android/raw/master/game/android-latest.json";
const PRODUCT_KEY = "crossingvoid-android-game";

export type AndroidArchiveChunk = {
  index: number;
  count: number;
  fileName: string;
  githubFileName: string;
  objectKey: string;
  sha256: string;
  sizeBytes: number;
};

export type AndroidArchiveAsset = {
  runtime: "Android";
  fileName: string;
  objectKey: string;
  sha256: string;
  sizeBytes: number;
  chunks: AndroidArchiveChunk[];
};

export type AndroidGameUpdateInfo = {
  version: string;
  channel: string;
  downloadReleaseTag: string;
  hasUpdate: boolean;
  message: string;
  asset: AndroidArchiveAsset;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" ? (value as Record<string, unknown>) : null;
}

function decodeManifestPayload(payload: unknown): unknown {
  if (typeof payload !== "string") return payload;
  try {
    return JSON.parse(payload.trim());
  } catch {
    throw new Error("Android 游戏清单 JSON 无法解析");
  }
}

function requireString(value: unknown, field: string) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`Android 更新清单缺少 ${field}`);
  return value;
}

function requirePositiveSafeInteger(value: unknown, field: string) {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`Android 更新清单中的 ${field} 无效`);
  }
  return value;
}

function requireSha256(value: unknown, field: string) {
  const normalized = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (!/^[a-f0-9]{64}$/.test(normalized)) throw new Error(`Android 更新清单中的 ${field} 缺少有效 SHA256`);
  return normalized;
}

function requireCanonicalChunkName(value: unknown, index: number) {
  const fileName = requireString(value, `latest.assets[0].chunks[${index - 1}].fileName`);
  const expected = `CrossingVoid手机端.碎片${String(index).padStart(3, "0")}`;
  if (fileName !== expected) throw new Error(`Android 第 ${index} 个分片不是标准文件名：应为 ${expected}`);
  return fileName;
}

function requireGithubChunkName(value: unknown, index: number) {
  const fileName = requireString(value, `latest.assets[0].chunks[${index - 1}].githubFileName`);
  const suffix = String(index).padStart(3, "0");
  const accepted = [`CrossingVoid手机端.碎片${suffix}`, `CrossingVoid.${suffix}`];
  if (!accepted.includes(fileName)) {
    throw new Error(`Android 第 ${index} 个 Github 分片文件名无效`);
  }
  return fileName;
}

export function parseAndroidUpdateCheckResponse(payload: unknown): AndroidGameUpdateInfo {
  const response = asRecord(decodeManifestPayload(payload));
  if (!response || response.schemaVersion !== 2 || response.productKey !== PRODUCT_KEY) {
    throw new Error("Android 游戏清单格式不受支持");
  }
  const release = asRecord(response.latest);
  const assets = release && Array.isArray(release.assets) ? release.assets : [];
  const asset = asRecord(assets.find((value) => asRecord(value)?.runtime === "Android"));
  if (!release || !asset) throw new Error("Android 更新清单缺少游戏资源");
  if (asset.runtime !== "Android") throw new Error("Android 更新清单没有 Android 资源");

  const rawChunks = Array.isArray(asset.chunks) ? asset.chunks : [];
  const chunks = rawChunks.length
    ? rawChunks.map((value, position) => {
        const chunk = asRecord(value);
        if (!chunk) throw new Error(`Android 更新清单第 ${position + 1} 个分片无效`);
        return {
          index: requirePositiveSafeInteger(chunk.index, `asset.chunks[${position}].index`),
          count: requirePositiveSafeInteger(chunk.count, `asset.chunks[${position}].count`),
          fileName: requireCanonicalChunkName(chunk.fileName, position + 1),
          githubFileName: requireGithubChunkName(chunk.githubFileName, position + 1),
          objectKey: requireString(chunk.objectKey, `asset.chunks[${position}].objectKey`),
          sha256: requireSha256(chunk.sha256, `asset.chunks[${position}].sha256`),
          sizeBytes: requirePositiveSafeInteger(chunk.sizeBytes, `asset.chunks[${position}].sizeBytes`),
        };
      })
    : [];

  return {
    version: requireString(release.version, "manifest.version"),
    channel: requireString(release.channel, "latest.channel"),
    downloadReleaseTag: requireString(response.downloadReleaseTag, "downloadReleaseTag"),
    hasUpdate: true,
    message: "",
    asset: {
      runtime: "Android",
      fileName: requireString(asset.fileName, "asset.fileName"),
      objectKey: requireString(asset.objectKey, "asset.objectKey"),
      sha256: requireSha256(asset.sha256, "asset.sha256"),
      sizeBytes: requirePositiveSafeInteger(asset.sizeBytes, "asset.sizeBytes"),
      chunks,
    },
  };
}

function versionParts(value: string) {
  const normalized = value.trim().replace(/^v/i, "").split("-")[0];
  return normalized.split(".").map((part) => Number.parseInt(part, 10) || 0);
}

export function isVersionAtLeast(current: string, target: string) {
  const currentParts = versionParts(current);
  const targetParts = versionParts(target);
  const count = Math.max(currentParts.length, targetParts.length);
  for (let index = 0; index < count; index += 1) {
    const left = currentParts[index] ?? 0;
    const right = targetParts[index] ?? 0;
    if (left !== right) return left > right;
  }
  return true;
}

export async function checkLatestAndroidGame(): Promise<AndroidGameUpdateInfo> {
  const separator = ANDROID_GAME_METADATA_MANIFEST_URL.includes("?") ? "&" : "?";
  const response = await CapacitorHttp.get({
    url: `${ANDROID_GAME_METADATA_MANIFEST_URL}${separator}t=${Date.now()}`,
    headers: { "Cache-Control": "no-cache" },
    responseType: "text",
  });
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`Android 游戏更新检查失败：HTTP ${response.status}`);
  }
  return parseAndroidUpdateCheckResponse(response.data);
}
