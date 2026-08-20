import { CapacitorHttp } from "@capacitor/core";

export const ANDROID_LAUNCHER_PRODUCT_KEY = "crossingvoid-launcher-android-installer";
export const ANDROID_LAUNCHER_MANIFEST_URL =
  "https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android/raw/master/launcher/android-installer-latest.json";

export type AndroidLauncherUpdateManifest = {
  schemaVersion: 1;
  productKey: typeof ANDROID_LAUNCHER_PRODUCT_KEY;
  versionName: string;
  versionCode: number;
  notes: string;
  publishedAt: string;
  asset: {
    fileName: string;
    url: string;
    sizeBytes: number;
    sha256: string;
  };
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" ? value as Record<string, unknown> : null;
}

function decodeManifestPayload(payload: unknown): unknown {
  if (typeof payload !== "string") return payload;
  try {
    return JSON.parse(payload.trim());
  } catch {
    throw new Error("启动器更新清单 JSON 无法解析");
  }
}

function requireString(value: unknown, label: string) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`启动器更新清单缺少 ${label}`);
  return value.trim();
}

export function parseAndroidLauncherManifest(payload: unknown): AndroidLauncherUpdateManifest {
  const source = asRecord(decodeManifestPayload(payload));
  const asset = asRecord(source?.asset);
  if (!source || !asset) throw new Error("启动器更新清单格式无效");
  if (source.schemaVersion !== 1) throw new Error("启动器更新清单版本不受支持");
  if (source.productKey !== ANDROID_LAUNCHER_PRODUCT_KEY) throw new Error("启动器更新产品标识不正确");

  const versionCode = Number(source.versionCode);
  const versionName = requireString(source.versionName, "versionName");
  const sizeBytes = Number(asset.sizeBytes);
  const sha256 = requireString(asset.sha256, "asset.sha256").toLowerCase();
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) throw new Error("启动器 versionCode 无效");
  if (!/^\d+\.\d+\.\d+$/.test(versionName)) throw new Error("手机版启动器版本号必须使用纯数字三段式");
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) throw new Error("启动器安装包大小无效");
  if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error("启动器安装包 SHA256 无效");

  return {
    schemaVersion: 1,
    productKey: ANDROID_LAUNCHER_PRODUCT_KEY,
    versionName,
    versionCode,
    notes: typeof source.notes === "string" ? source.notes : "",
    publishedAt: typeof source.publishedAt === "string" ? source.publishedAt : "",
    asset: {
      fileName: requireString(asset.fileName, "asset.fileName"),
      url: requireString(asset.url, "asset.url"),
      sizeBytes,
      sha256,
    },
  };
}

export function shouldInstallLauncherUpdate(
  currentVersionCode: number,
  currentVersionName: string,
  manifest: AndroidLauncherUpdateManifest,
) {
  if (compareVersionNames(currentVersionName, manifest.versionName) >= 0) return false;
  return manifest.versionCode >= currentVersionCode;
}

function compareVersionNames(left: string, right: string) {
  const current = left.split(".").map((part) => Number.parseInt(part, 10) || 0);
  const target = right.split(".").map((part) => Number.parseInt(part, 10) || 0);
  const length = Math.max(current.length, target.length);
  for (let index = 0; index < length; index += 1) {
    if ((current[index] ?? 0) !== (target[index] ?? 0)) {
      return (current[index] ?? 0) < (target[index] ?? 0) ? -1 : 1;
    }
  }
  return 0;
}

export async function checkLatestAndroidLauncher(): Promise<AndroidLauncherUpdateManifest | null> {
  const separator = ANDROID_LAUNCHER_MANIFEST_URL.includes("?") ? "&" : "?";
  const response = await CapacitorHttp.get({
    url: `${ANDROID_LAUNCHER_MANIFEST_URL}${separator}t=${Date.now()}`,
    headers: { "Cache-Control": "no-cache" },
    responseType: "text",
  });
  if (response.status === 404) return null;
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`启动器更新检查失败：HTTP ${response.status}`);
  }
  return parseAndroidLauncherManifest(response.data);
}
