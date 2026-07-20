import { CapacitorHttp } from "@capacitor/core";

const UPDATE_CHECK_URL = "https://www.crossingvoid.top/api/toolbox-updates/check";
const PRODUCT_KEY = "crossingvoid-android-game";

export type AndroidArchiveChunk = {
  index: number;
  count: number;
  fileName: string;
  objectKey: string;
  sha256?: string;
  sizeBytes?: number;
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
  hasUpdate: boolean;
  message: string;
  asset: AndroidArchiveAsset;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" ? (value as Record<string, unknown>) : null;
}

function requireString(value: unknown, field: string) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`Android 更新清单缺少 ${field}`);
  return value;
}

export function parseAndroidUpdateCheckResponse(payload: unknown): AndroidGameUpdateInfo {
  const response = asRecord(payload);
  if (!response) throw new Error("Android 更新接口返回了无效数据");
  if (response.success !== true) {
    throw new Error(typeof response.message === "string" ? response.message : "Android 更新检查失败");
  }

  const manifest = asRecord(response.manifest);
  const asset = asRecord(manifest?.asset);
  if (!manifest || !asset) throw new Error("Android 更新清单缺少游戏资源");
  if (manifest.productKey !== PRODUCT_KEY) throw new Error("Android 更新清单产品标识不正确");
  if (asset.runtime !== "Android") throw new Error("Android 更新清单没有 Android 资源");

  const rawChunks = Array.isArray(asset.chunks) ? asset.chunks : [];
  const chunks = rawChunks.length
    ? rawChunks.map((value, position) => {
        const chunk = asRecord(value);
        if (!chunk) throw new Error(`Android 更新清单第 ${position + 1} 个分片无效`);
        return {
          index: Number(chunk.index ?? position + 1),
          count: Number(chunk.count ?? rawChunks.length),
          fileName: requireString(chunk.fileName, `asset.chunks[${position}].fileName`),
          objectKey: requireString(chunk.objectKey, `asset.chunks[${position}].objectKey`),
          sha256: typeof chunk.sha256 === "string" ? chunk.sha256 : undefined,
          sizeBytes: typeof chunk.sizeBytes === "number" ? chunk.sizeBytes : undefined,
        };
      })
    : [];

  return {
    version: requireString(manifest.version, "manifest.version"),
    channel: typeof manifest.channel === "string" ? manifest.channel : "stable",
    hasUpdate: response.hasUpdate === true,
    message: typeof response.message === "string" ? response.message : "",
    asset: {
      runtime: "Android",
      fileName: requireString(asset.fileName, "asset.fileName"),
      objectKey: requireString(asset.objectKey, "asset.objectKey"),
      sha256: typeof asset.sha256 === "string" ? asset.sha256 : "",
      sizeBytes: typeof asset.sizeBytes === "number" ? asset.sizeBytes : 0,
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
  const response = await CapacitorHttp.post({
    url: UPDATE_CHECK_URL,
    headers: { "Content-Type": "application/json" },
    data: {
      productKey: PRODUCT_KEY,
      currentVersion: "0.0.0",
      channel: "stable",
      runtime: "Android",
    },
  });
  return parseAndroidUpdateCheckResponse(response.data);
}
