/**
 * 游戏包（清单 v1）统一内核：解析、校验、差异计划。
 *
 * 纯逻辑，不碰任何平台 API。**这份文件在 PC 与 Android 两个仓库里逐字相同**，
 * 两边各有一条测试比对内容 sha256（换行统一按 LF 计算），谁改了另一边就会红。
 *
 * 契约见 `Docs/GameDownloadRetrofitPlan.md` 与 `D:\2026-09-20-game-upload-launcher-contract.md`。
 */

/** 内核版本号：改动这份文件的语义时一起抬，两端比对测试会用到。 */
export const GAME_PACKAGE_CORE_VERSION = "1";

/** 下载站根地址；清单与文件都在它下面。 */
export const GAME_PACKAGE_BASE_URL = "https://dl.crossingvoid.top";

/** 游戏包所在的 GitHub 仓库（备用源）。 */
export const GAME_PACKAGE_GITHUB_REPOSITORY = "kirito0000001/CrossingVoid";

export type GamePackagePlatform = "Windows" | "Android";
export type GamePackageChannel = "stable" | "test";

export type GamePackageFile = {
  path: string;
  sizeBytes: number;
  sha256: string;
};

export type LatestPointer = {
  schemaVersion: 1;
  productSegment: string;
  version: string;
  manifestUrl: string;
  generatedAt: string;
};

export type GamePackageManifest = {
  schemaVersion: 1;
  productKey: string;
  runtime: GamePackagePlatform;
  version: string;
  channel: GamePackageChannel;
  baseUrl: string;
  generatedAt: string;
  files: GamePackageFile[];
  patches: unknown[];
};

/** 本地状态文件：与清单 `files[]` 同形，两端同格式。 */
export type GamePackageState = {
  schemaVersion: 1;
  productKey: string;
  version: string;
  files: GamePackageFile[];
};

export type GamePackagePlan = {
  download: GamePackageFile[];
  prune: string[];
  keep: string[];
  totalBytes: number;
};

export type GamePackageExpectation = {
  productKey: string;
  runtime: GamePackagePlatform;
  channel?: GamePackageChannel;
};

export type GamePackageErrorCode =
  | "manifest-unsupported"
  | "manifest-invalid"
  | "product-mismatch"
  | "runtime-mismatch"
  | "channel-mismatch"
  | "version-downgrade"
  | "version-retired"
  | "hash-mismatch"
  | "disk-full"
  | "permission-denied"
  | "network-interrupted"
  | "cancelled";

export class GamePackageError extends Error {
  readonly code: GamePackageErrorCode;

  constructor(code: GamePackageErrorCode, message: string) {
    super(message);
    this.name = "GamePackageError";
    this.code = code;
  }
}

const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const ILLEGAL_PATH_CHARACTERS = /[<>:"|?*]/;

export function gamePackageManifestUrl(productSegment: string) {
  return `${GAME_PACKAGE_BASE_URL}/games/${productSegment}/latest.json`;
}

/** 下载站的文件地址：`baseUrl + path`（baseUrl 以 `/` 结尾）。 */
export function officialGamePackageFileUrl(baseUrl: string, path: string) {
  const base = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  return `${base}${path.trim().replace(/\\/g, "/").replace(/^\/+/, "")}`;
}

/**
 * GitHub Release 附件的命名规则：路径里的 `/` 换成 `__`。
 *
 * 例如 `CrossingVoid/Binaries/Win64/CrossingVoid-Win64-Shipping.exe`
 * → `CrossingVoid__Binaries__Win64__CrossingVoid-Win64-Shipping.exe`。
 * 根目录文件保持原名。上传侧与这里必须一致。
 */
export function githubGameAssetName(path: string) {
  return path
    .trim()
    .replace(/\\/g, "/")
    .split("/")
    .filter((segment) => segment && segment !== ".")
    .join("__");
}

/** Release 标签：`PC-V0.5.14` / `Android-V0.5.14`（版本号自带 v 时只保留一个）。 */
export function githubGameReleaseTag(platformPrefix: string, version: string) {
  return `${platformPrefix.trim()}-V${version.trim().replace(/^v/i, "")}`;
}

/** GitHub Release 的下载基址（末尾带 `/`）。 */
export function githubGameReleaseBaseUrl(repository: string, tag: string) {
  const repo = repository.trim().replace(/^\/+|\/+$/g, "");
  return `https://github.com/${repo}/releases/download/${encodeURIComponent(tag)}/`;
}

/** Release 里那份清单附件（上传侧固定叫这个名字）。 */
export const GITHUB_GAME_MANIFEST_ASSET = "manifest.json";

/** GitHub Release 里清单附件的下载地址。 */
export function githubGameManifestUrl(repository: string, tag: string) {
  return `${githubGameReleaseBaseUrl(repository, tag)}${encodeURIComponent(GITHUB_GAME_MANIFEST_ASSET)}`;
}

export type GitHubReleaseAssetSummary = {
  id?: number;
  name?: string;
  size?: number;
  digest?: string;
};

export type GitHubReleaseSummary = {
  tag_name?: string;
  name?: string;
  draft?: boolean;
  prerelease?: boolean;
  created_at?: string;
  assets?: GitHubReleaseAssetSummary[];
};

/**
 * 从 Release 列表里挑出"本平台、且带清单附件"的那一条。
 *
 * 只认标签前缀（`PC-V` / `Android-V`）与附件名（`manifest.json`），
 * 所以列表里混着安卓/PC/旧流程的 release 也不会挑错。
 */
export function pickGitHubGameRelease(
  releases: readonly GitHubReleaseSummary[],
  options: { tagPrefix: string; assetName?: string; allowPrerelease?: boolean },
) {
  const prefix = options.tagPrefix.trim().toLowerCase();
  const assetName = (options.assetName ?? GITHUB_GAME_MANIFEST_ASSET).trim().toLowerCase();
  if (!prefix || !assetName) return null;

  for (const release of releases) {
    if (release.draft || (!options.allowPrerelease && release.prerelease)) continue;
    const tag = (release.tag_name ?? "").trim();
    if (!tag.toLowerCase().startsWith(prefix)) continue;
    const asset = (release.assets ?? []).find(
      (item) => (item.name ?? "").trim().toLowerCase() === assetName,
    );
    if (asset) return { tag, asset };
  }
  return null;
}

export type GamePackageSourceKey = "official" | "github";

/**
 * 每个文件的候选下载地址：**首选源排第一，另一个源兜底**。
 *
 * 这样"双源混用"是天然的：某个文件在首选源上 404 或校验失败时，
 * 同一个文件会自动从另一个源取；一次会话里两个源可以同时供文件。
 * 被渠道开关关掉的源不会进列表。
 */
export function buildGamePackageUrlCandidates(options: {
  path: string;
  officialBaseUrl: string;
  githubReleaseBaseUrl: string;
  preferred: GamePackageSourceKey;
  officialEnabled?: boolean;
  githubEnabled?: boolean;
}): string[] {
  const official = options.officialEnabled === false
    ? ""
    : officialGamePackageFileUrl(options.officialBaseUrl, options.path);
  const github = options.githubEnabled === false || !options.githubReleaseBaseUrl
    ? ""
    : `${options.githubReleaseBaseUrl}${encodeURIComponent(githubGameAssetName(options.path))}`;
  const ordered = options.preferred === "github" ? [github, official] : [official, github];
  return ordered.filter((url) => Boolean(url));
}

export function withCacheBuster(url: string, now: number = Date.now()) {
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}t=${now}`;
}

export function normalizeGamePackagePath(path: string) {
  return path.trim().replace(/\\/g, "/").replace(/^\.\//, "").toLowerCase();
}

/**
 * 路径安全：相对路径、无盘符、无开头斜杠、无 `..`、无非法字符。
 * 这一条与 Rust 侧 `safe_relative_path` 必须保持一致。
 */
export function isSafeGamePackagePath(path: string) {
  const normalized = path.trim().replace(/\\/g, "/");
  if (!normalized) return false;
  if (normalized.startsWith("/")) return false;
  if (/^[a-zA-Z]:/.test(normalized)) return false;
  const segments = normalized.split("/").filter((segment) => segment && segment !== ".");
  if (!segments.length) return false;
  return segments.every(
    (segment) => segment !== ".." && !ILLEGAL_PATH_CHARACTERS.test(segment),
  );
}

function asRecord(value: unknown, message: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new GamePackageError("manifest-invalid", message);
  }
  return value as Record<string, unknown>;
}

function asTrimmedString(value: unknown) {
  return typeof value === "string" ? value.trim() : "";
}

export function parseLatestPointer(payload: unknown): LatestPointer {
  const record = asRecord(payload, "下载站返回的最新版指针格式无效。");
  if (record.schemaVersion !== 1) {
    throw new GamePackageError(
      "manifest-unsupported",
      `下载站指针版本不受支持：${String(record.schemaVersion)}。`,
    );
  }
  const productSegment = asTrimmedString(record.productSegment);
  const version = asTrimmedString(record.version);
  const manifestUrl = asTrimmedString(record.manifestUrl);
  if (!productSegment || !version || !manifestUrl) {
    throw new GamePackageError("manifest-invalid", "下载站最新版指针缺少必要字段。");
  }
  if (!/^https?:\/\//i.test(manifestUrl)) {
    throw new GamePackageError("manifest-invalid", "下载站最新版指针里的清单地址无效。");
  }
  return {
    schemaVersion: 1,
    productSegment,
    version,
    manifestUrl,
    generatedAt: asTrimmedString(record.generatedAt),
  };
}

function parseGamePackageFile(value: unknown, index: number): GamePackageFile {
  const record = asRecord(value, `清单里第 ${index + 1} 条文件记录格式无效。`);
  const path = asTrimmedString(record.path);
  if (!isSafeGamePackagePath(path)) {
    throw new GamePackageError(
      "manifest-invalid",
      `清单里的文件路径不安全：${path || "(空)"}`,
    );
  }
  const sha256 = asTrimmedString(record.sha256).toLowerCase();
  if (!SHA256_PATTERN.test(sha256)) {
    throw new GamePackageError("manifest-invalid", `清单里 ${path} 的 sha256 不是 64 位小写十六进制。`);
  }
  const sizeBytes = Number(record.sizeBytes);
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) {
    throw new GamePackageError("manifest-invalid", `清单里 ${path} 的 sizeBytes 无效。`);
  }
  return { path, sizeBytes, sha256 };
}

export function parseGamePackageManifest(
  payload: unknown,
  expectation: GamePackageExpectation,
): GamePackageManifest {
  const record = asRecord(payload, "游戏更新清单格式无效。");
  if (record.schemaVersion !== 1) {
    throw new GamePackageError(
      "manifest-unsupported",
      `游戏更新清单版本不受支持（收到 schemaVersion=${String(record.schemaVersion)}）。`,
    );
  }
  const productKey = asTrimmedString(record.productKey);
  if (productKey !== expectation.productKey) {
    throw new GamePackageError("product-mismatch", "这份清单不属于当前游戏，已停止更新。");
  }
  const runtime = asTrimmedString(record.runtime);
  if (runtime !== expectation.runtime) {
    throw new GamePackageError("runtime-mismatch", "清单的平台与当前启动器不一致。");
  }
  const channel = asTrimmedString(record.channel) || "stable";
  if (expectation.channel && channel !== expectation.channel) {
    throw new GamePackageError("channel-mismatch", "清单的通道与当前设置不一致。");
  }
  const version = asTrimmedString(record.version);
  if (!version) {
    throw new GamePackageError("manifest-invalid", "清单缺少版本号。");
  }
  const baseUrl = asTrimmedString(record.baseUrl);
  if (!/^https?:\/\//i.test(baseUrl)) {
    throw new GamePackageError("manifest-invalid", "清单里的下载基址无效。");
  }
  const filesPayload = record.files;
  if (!Array.isArray(filesPayload) || filesPayload.length === 0) {
    throw new GamePackageError("manifest-invalid", "清单里没有任何文件。");
  }

  const files: GamePackageFile[] = [];
  const seen = new Set<string>();
  filesPayload.forEach((item, index) => {
    const entry = parseGamePackageFile(item, index);
    const key = normalizeGamePackagePath(entry.path);
    if (seen.has(key)) {
      throw new GamePackageError("manifest-invalid", `清单里有重复的文件路径：${entry.path}`);
    }
    seen.add(key);
    files.push(entry);
  });

  return {
    schemaVersion: 1,
    productKey,
    runtime: runtime as GamePackagePlatform,
    version,
    channel: channel as GamePackageChannel,
    baseUrl: baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`,
    generatedAt: asTrimmedString(record.generatedAt),
    files,
    // patches 本期恒为空，按契约忽略而不是报错。
    patches: Array.isArray(record.patches) ? record.patches : [],
  };
}

/** 读本地状态文件：形状不对就视为"没有状态"，交给上层走一次本地扫描。 */
export function parseGamePackageState(payload: unknown): GamePackageState | null {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const record = payload as Record<string, unknown>;
  if (!Array.isArray(record.files)) return null;

  const files: GamePackageFile[] = [];
  for (const item of record.files) {
    if (!item || typeof item !== "object" || Array.isArray(item)) continue;
    const entry = item as Record<string, unknown>;
    const path = asTrimmedString(entry.path);
    const sha256 = asTrimmedString(entry.sha256).toLowerCase();
    const sizeBytes = Number(entry.sizeBytes);
    if (!isSafeGamePackagePath(path) || !SHA256_PATTERN.test(sha256)) continue;
    if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) continue;
    files.push({ path, sizeBytes, sha256 });
  }

  return {
    schemaVersion: 1,
    productKey: asTrimmedString(record.productKey),
    version: asTrimmedString(record.version),
    files,
  };
}

/**
 * 差异计划：`sha256` 不同就下、本地多出来的（上次状态里记过、这次清单没有）就删。
 *
 * `localFiles` 传 `null` 表示"还不知道本地有什么"（没有状态文件），
 * 这时把所有文件都当成要下 —— 上层应该先用 `scan_local_game_package` 扫一遍再进来。
 */
export function buildGamePackagePlan(
  manifest: GamePackageManifest,
  localFiles: GamePackageFile[] | null,
  previousPaths: string[] = [],
): GamePackagePlan {
  const localByPath = new Map<string, GamePackageFile>();
  for (const entry of localFiles ?? []) {
    localByPath.set(normalizeGamePackagePath(entry.path), entry);
  }

  const download: GamePackageFile[] = [];
  const keep: string[] = [];
  const manifestPaths = new Set<string>();

  for (const entry of manifest.files) {
    const key = normalizeGamePackagePath(entry.path);
    manifestPaths.add(key);
    const local = localByPath.get(key);
    if (local && local.sha256.toLowerCase() === entry.sha256.toLowerCase()) {
      keep.push(entry.path);
      continue;
    }
    download.push(entry);
  }

  const prune = previousPaths.filter(
    (path) => !manifestPaths.has(normalizeGamePackagePath(path)),
  );

  const totalBytes = download.reduce((sum, entry) => sum + entry.sizeBytes, 0);

  return { download, prune, keep, totalBytes };
}

/** 版本比较：把语义版本拆成数字段比大小，够用且两端一致。 */
export function compareGamePackageVersions(left: string, right: string) {
  const split = (value: string) =>
    value
      .trim()
      .replace(/^v/i, "")
      .split(/[.-]/)
      .map((part) => Number.parseInt(part, 10))
      .map((part) => (Number.isFinite(part) ? part : 0));
  const leftParts = split(left);
  const rightParts = split(right);
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const leftValue = leftParts[index] ?? 0;
    const rightValue = rightParts[index] ?? 0;
    if (leftValue !== rightValue) return leftValue - rightValue;
  }
  return 0;
}

/** 清单版本低于本地版本时不要静默降级。 */
export function assertNoGamePackageDowngrade(
  manifest: GamePackageManifest,
  localVersion: string,
) {
  if (!localVersion.trim()) return;
  if (compareGamePackageVersions(manifest.version, localVersion) < 0) {
    throw new GamePackageError(
      "version-downgrade",
      `线上版本（${manifest.version}）低于本地版本（${localVersion}），已停止自动更新。`,
    );
  }
}

/** 把错误翻成给玩家看的中文；未知错误原样抛出。 */
export function describeGamePackageError(error: unknown): string {
  if (error instanceof GamePackageError) {
    switch (error.code) {
      case "manifest-unsupported":
        return "游戏更新清单格式不受支持，请更新启动器。";
      case "product-mismatch":
        return "这份清单不属于当前游戏，已停止更新。";
      case "runtime-mismatch":
        return "清单的平台与当前启动器不一致。";
      case "channel-mismatch":
        return "清单的通道与当前设置不一致。";
      case "version-downgrade":
        return error.message;
      case "version-retired":
        return "该版本已下线，请更新到最新版。";
      case "hash-mismatch":
        return "文件校验失败，请重试。";
      case "disk-full":
        return "磁盘空间不足，请清理后重试。";
      case "permission-denied":
        return "没有写入权限，请检查安装目录。";
      case "network-interrupted":
        return "网络中断，可继续下载。";
      case "cancelled":
        return "已取消下载。";
      default:
        return error.message;
    }
  }
  return error instanceof Error ? error.message : String(error);
}
