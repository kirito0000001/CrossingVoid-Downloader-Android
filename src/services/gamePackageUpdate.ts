import { CapacitorHttp } from "@capacitor/core";

import {
  GAME_PACKAGE_GITHUB_REPOSITORY,
  GamePackageError,
  buildGamePackagePlan,
  buildGamePackageUrlCandidates,
  gamePackageManifestUrl,
  githubGameReleaseBaseUrl,
  githubGameReleaseTag,
  githubGameManifestUrl,
  pickGitHubGameRelease,
  parseGamePackageManifest,
  parseGamePackageState,
  parseLatestPointer,
  withCacheBuster,
  type GitHubReleaseSummary,
  type GamePackageFile,
  type GamePackageManifest,
  type GamePackagePlan,
  type GamePackageState,
} from "./gamePackage";

/**
 * 安卓侧的清单 v1 对接层（与 PC 共用 `gamePackage.ts` 内核）。
 *
 * 与老的分片链路（`gameUpdate.ts` / `downloadPlan.ts`）并存：
 * 这一层先落地"解析 + 差异计划"，等原生下载器改成按文件下载后
 * 再把 App.vue 的入口切过来。
 */

export const ANDROID_GAME_PACKAGE_SEGMENT = "crossingvoid-android";
export const ANDROID_GAME_PACKAGE_PRODUCT_KEY = "crossingvoid-android-game";
export const ANDROID_GAME_PACKAGE_RUNTIME = "Android" as const;
/** 打包时写入的 OBB 旁车（`<工程名>.obb.json`）。 */
export const ANDROID_OBB_SIDECAR_PATH = "CrossingVoid.obb.json";
export const ANDROID_VERSION_MARKER_PATH = "CrossingVoid.version.json";

/** 旁车：告诉启动器要组装的 OBB 叫什么、由哪些条目组成。 */
export type AndroidObbSidecar = {
  obbFileName: string;
  entries: string[];
  version: string;
  productKey: string;
};

export type AndroidGamePackage = {
  version: string;
  channel: string;
  productKey: string;
  baseUrl: string;
  files: GamePackageFile[];
  obb: AndroidObbSidecar | null;
  /** 清单是从哪个 GitHub Release 拿的（下载站挂了时用它拼备用源地址）。 */
  githubTag: string;
};

export type AndroidPackageFileKind = "apk" | "obb-entry" | "metadata" | "obb-sidecar";

export type AndroidDownloadFile = GamePackageFile & {
  /** 首选地址（等于 urls[0]，保留给旧调用）。 */
  url: string;
  /** 候选地址：首选源在前，另一个源兜底。 */
  urls: string[];
  kind: AndroidPackageFileKind;
};

export type AndroidGameDownloadPlan = {
  productKey: string;
  runtime: "Android";
  version: string;
  baseUrl: string;
  /** 本次真正要下的文件（sha256 变了的那些）。 */
  files: AndroidDownloadFile[];
  /** 清单全量，用来写本地状态。 */
  manifestFiles: GamePackageFile[];
  prune: string[];
  totalBytes: number;
  apk: GamePackageFile | null;
  obb: AndroidObbSidecar | null;
  /** 首次安装、或 OBB 里非 pak 条目变了 → 需要重组 OBB。 */
  needsObbRebuild: boolean;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asTrimmedString(value: unknown) {
  return typeof value === "string" ? value.trim() : "";
}

export function parseAndroidObbSidecar(payload: unknown): AndroidObbSidecar {
  const record = asRecord(payload);
  if (!record) throw new GamePackageError("manifest-invalid", "OBB 旁车格式无效。");
  const obbFileName = asTrimmedString(record.obbFileName);
  const entries = Array.isArray(record.entries)
    ? record.entries.map((value) => asTrimmedString(value)).filter(Boolean)
    : [];
  if (!obbFileName || entries.length === 0) {
    throw new GamePackageError("manifest-invalid", "OBB 旁车缺少 obbFileName 或 entries。");
  }
  if (!/^(main|patch)\.\d+\.[A-Za-z0-9_.]+\.obb$/i.test(obbFileName)) {
    throw new GamePackageError("manifest-invalid", `OBB 文件名不符合规范：${obbFileName}`);
  }
  return {
    obbFileName,
    entries,
    version: asTrimmedString(record.version),
    productKey: asTrimmedString(record.productKey),
  };
}

/** 清单里的这条文件是什么角色。 */
export function classifyAndroidPackageFile(
  path: string,
  obb: AndroidObbSidecar | null,
): AndroidPackageFileKind {
  const normalized = path.trim().replace(/\\/g, "/");
  if (/\.apk$/i.test(normalized)) return "apk";
  if (normalized === ANDROID_OBB_SIDECAR_PATH) return "obb-sidecar";
  if (obb?.entries.some((entry) => entry.replace(/\\/g, "/") === normalized)) return "obb-entry";
  return "metadata";
}

export function toAndroidDownloadFile(
  baseUrl: string,
  file: GamePackageFile,
  obb: AndroidObbSidecar | null,
): AndroidDownloadFile {
  const base = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  const url = `${base}${file.path.replace(/^\/+/, "")}`;
  return {
    ...file,
    url,
    urls: [url],
    kind: classifyAndroidPackageFile(file.path, obb),
  };
}

/**
 * 差异计划：只看 sha256；本地多出来的进 prune。
 *
 * OBB 是否要重组按规划文档 D.3：
 * - 首次安装（没有本地状态）→ 要；
 * - 变化里出现非 `CrossingVoid/Content/Paks/*` 的 OBB 条目（Engine/…）→ 要；
 * - 只有 pak 变化 → 理论上可以走 `Saved/Paks` 快速路径，但优先级还没在真机验过，
 *   所以本期依然重建（`needsObbRebuild` 保持 true，等真机结论再开）。
 */
export function buildAndroidGameDownloadPlan(
  pkg: AndroidGamePackage,
  localState: GamePackageState | null,
  options: {
    source?: "official" | "github";
    officialEnabled?: boolean;
    githubEnabled?: boolean;
  } = {},
): AndroidGameDownloadPlan {
  const preferred = options.source ?? "official";
  const officialEnabled = options.officialEnabled ?? true;
  const githubEnabled = options.githubEnabled ?? true;
  // 备用源：同一个 Release 里，附件名就是把路径里的 `/` 换成 `__`。
  const githubReleaseBaseUrl = githubGameReleaseBaseUrl(
    GAME_PACKAGE_GITHUB_REPOSITORY,
    pkg.githubTag || githubGameReleaseTag("Android", pkg.version),
  );
  const manifest: GamePackageManifest = {
    schemaVersion: 1,
    productKey: pkg.productKey,
    runtime: "Android",
    version: pkg.version,
    channel: (pkg.channel === "test" ? "test" : "stable") as GamePackageManifest["channel"],
    baseUrl: pkg.baseUrl,
    generatedAt: "",
    files: pkg.files,
    patches: [],
  };
  const corePlan: GamePackagePlan = buildGamePackagePlan(
    manifest,
    localState?.files ?? null,
    localState?.files.map((entry) => entry.path) ?? [],
  );

  const files = corePlan.download.map((file) => {
    const urls = buildGamePackageUrlCandidates({
      path: file.path,
      officialBaseUrl: pkg.baseUrl,
      githubReleaseBaseUrl,
      preferred,
      officialEnabled,
      githubEnabled,
    });
    return {
      ...file,
      url: urls[0] ?? "",
      urls,
      kind: classifyAndroidPackageFile(file.path, pkg.obb),
    };
  });
  const apk = pkg.files.find((file) => classifyAndroidPackageFile(file.path, pkg.obb) === "apk") ?? null;

  const firstInstall = !localState || localState.files.length === 0;
  const changedNonPakObbEntry = files.some(
    (file) =>
      file.kind === "obb-entry" &&
      !file.path.replace(/\\/g, "/").toLowerCase().startsWith("crossingvoid/content/paks/"),
  );

  return {
    productKey: pkg.productKey,
    runtime: "Android",
    version: pkg.version,
    baseUrl: pkg.baseUrl,
    files,
    manifestFiles: pkg.files,
    prune: corePlan.prune,
    totalBytes: corePlan.totalBytes,
    apk,
    obb: pkg.obb,
    needsObbRebuild: Boolean(pkg.obb) && (firstInstall || changedNonPakObbEntry),
  };
}

async function fetchJson(url: string): Promise<unknown> {
  const response = await CapacitorHttp.get({
    url: withCacheBuster(url),
    headers: { "Cache-Control": "no-cache" },
    responseType: "text",
  });
  if (response.status < 200 || response.status >= 300) {
    throw new GamePackageError("network-interrupted", `下载站返回 HTTP ${response.status}。`);
  }
  const data = response.data;
  if (typeof data === "string") {
    try {
      return JSON.parse(data.trim());
    } catch {
      throw new GamePackageError("manifest-invalid", "下载站返回的内容不是合法 JSON。");
    }
  }
  return data;
}

/** GitHub Release 列表接口（备用源的清单入口）。 */
export function githubGameReleasesUrl() {
  const repo = GAME_PACKAGE_GITHUB_REPOSITORY.trim().replace(/^\/+|\/+$/g, "");
  return `https://api.github.com/repos/${repo}/releases?per_page=10`;
}

/**
 * 拉最新版指针 → 清单 → （若有）OBB 旁车。
 *
 * 双源：下载站优先；下载站挂了（或被渠道开关关掉）就从 GitHub Release 里那份
 * `manifest.json` 读同一份清单 —— 两份是同一产物，sha256 完全一致。
 *
 * 渠道开关来自 PC 开发页发布的 `launcher-download-channels.json`
 * （见 `remoteLauncherInfo.ts`）：被关掉的源连"试一下"都不做。
 */
export type AndroidGamePackageRequest = {
  productSegment?: string;
  productKey?: string;
  officialEnabled?: boolean;
  githubEnabled?: boolean;
};

export async function fetchAndroidGamePackage(
  request: AndroidGamePackageRequest = {},
): Promise<AndroidGamePackage> {
  const productSegment = request.productSegment ?? ANDROID_GAME_PACKAGE_SEGMENT;
  const productKey = request.productKey ?? ANDROID_GAME_PACKAGE_PRODUCT_KEY;
  const expectation = { productKey, runtime: ANDROID_GAME_PACKAGE_RUNTIME };
  const officialEnabled = request.officialEnabled ?? true;
  const githubEnabled = request.githubEnabled ?? true;
  const failures: string[] = [];
  let manifest: GamePackageManifest | null = null;
  let githubTag = "";

  if (officialEnabled) {
    try {
      const pointer = parseLatestPointer(await fetchJson(gamePackageManifestUrl(productSegment)));
      manifest = parseGamePackageManifest(await fetchJson(pointer.manifestUrl), expectation);
      githubTag = githubGameReleaseTag("Android", manifest.version);
    } catch (error) {
      failures.push(error instanceof Error ? error.message : String(error));
    }
  }

  if (!manifest && githubEnabled) {
    try {
      const releases = await fetchJson(githubGameReleasesUrl()) as GitHubReleaseSummary[];
      const picked = pickGitHubGameRelease(releases, { tagPrefix: "Android-V" });
      if (!picked) throw new GamePackageError("manifest-invalid", "GitHub 上没有找到安卓游戏包。");
      manifest = parseGamePackageManifest(
        await fetchJson(githubGameManifestUrl(GAME_PACKAGE_GITHUB_REPOSITORY, picked.tag)),
        expectation,
      );
      githubTag = picked.tag;
    } catch (error) {
      failures.push(error instanceof Error ? error.message : String(error));
    }
  }

  if (!manifest) {
    throw new GamePackageError(
      "network-interrupted",
      failures.length > 0
        ? `无法读取游戏清单：${failures.join("；")}`
        : "当前没有开放的下载渠道，暂时无法获取游戏清单。",
    );
  }

  const sidecar = manifest.files.find(
    (file) => file.path.replace(/\\/g, "/") === ANDROID_OBB_SIDECAR_PATH,
  );
  const obb = sidecar
    ? parseAndroidObbSidecar(await fetchJson(toAndroidDownloadFile(manifest.baseUrl, sidecar, null).url))
    : null;

  return {
    version: manifest.version,
    channel: manifest.channel,
    productKey: manifest.productKey,
    baseUrl: manifest.baseUrl,
    files: manifest.files,
    obb,
    githubTag,
  };
}

export { parseGamePackageState };
