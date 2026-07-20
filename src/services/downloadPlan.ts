import type { AndroidGameUpdateInfo } from "./gameUpdate";

export type NativeDownloadChunk = {
  index: number;
  count: number;
  fileName: string;
  objectKey: string;
  sha256: string;
  sizeBytes: number;
  downloadUrl?: string;
};

export type AndroidDownloadSource = "official" | "github";

export type AndroidDownloadPlan = {
  productKey: "crossingvoid-android-game";
  runtime: "Android";
  source: AndroidDownloadSource;
  version: string;
  archiveFileName: string;
  archiveSha256: string;
  totalBytes: number;
  chunks: NativeDownloadChunk[];
};

export type LauncherDownloadPhase =
  | "updateReady"
  | "downloading"
  | "paused"
  | "verifying"
  | "readyInstall"
  | "error";

function requireSha256(value: string | undefined, label: string) {
  const normalized = value?.trim().toLowerCase() ?? "";
  if (!/^[a-f0-9]{64}$/.test(normalized)) throw new Error(`${label}缺少有效 SHA256`);
  return normalized;
}

function githubChunkUrl(version: string, fileName: string) {
  const tag = `Android-${version}`;
  return `https://github.com/kirito0000001/CrossingVoid/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(fileName)}`;
}

export function buildAndroidDownloadPlan(
  update: AndroidGameUpdateInfo,
  source: AndroidDownloadSource = "official",
): AndroidDownloadPlan {
  const chunks = [...update.asset.chunks].sort((left, right) => left.index - right.index);
  if (chunks.length === 0) throw new Error("Android 更新清单没有下载分片");

  const normalized = chunks.map((chunk, position) => {
    const expectedIndex = position + 1;
    if (chunk.index !== expectedIndex) throw new Error(`Android 分片序号不连续：应为 ${expectedIndex}，实际为 ${chunk.index}`);
    if (chunk.count !== chunks.length) throw new Error(`Android 分片数量不一致：清单声明 ${chunk.count}，实际为 ${chunks.length}`);
    if (!chunk.fileName.trim() || !chunk.objectKey.trim()) throw new Error(`Android 第 ${expectedIndex} 个分片缺少文件信息`);
    if (!Number.isSafeInteger(chunk.sizeBytes) || (chunk.sizeBytes ?? 0) <= 0) throw new Error(`Android 第 ${expectedIndex} 个分片大小无效`);

    return {
      index: expectedIndex,
      count: chunks.length,
      fileName: chunk.fileName,
      objectKey: chunk.objectKey,
      sha256: requireSha256(chunk.sha256, `Android 第 ${expectedIndex} 个分片`),
      sizeBytes: chunk.sizeBytes!,
      ...(source === "github" ? { downloadUrl: githubChunkUrl(update.version, chunk.fileName) } : {}),
    };
  });

  const chunkBytes = normalized.reduce((total, chunk) => total + chunk.sizeBytes, 0);
  if (chunkBytes !== update.asset.sizeBytes) {
    throw new Error(`Android 分片总大小 ${chunkBytes} 与完整包 ${update.asset.sizeBytes} 不一致`);
  }

  return {
    productKey: "crossingvoid-android-game",
    runtime: "Android",
    source,
    version: update.version,
    archiveFileName: update.asset.fileName,
    archiveSha256: requireSha256(update.asset.sha256, "Android 完整包"),
    totalBytes: update.asset.sizeBytes,
    chunks: normalized,
  };
}

export function launcherPhaseFromNativeState(status: string): LauncherDownloadPhase {
  if (status === "downloading") return "downloading";
  if (status === "paused") return "paused";
  if (["verifying", "merging", "extracting"].includes(status)) return "verifying";
  if (status === "ready") return "readyInstall";
  if (status === "error") return "error";
  return "updateReady";
}
