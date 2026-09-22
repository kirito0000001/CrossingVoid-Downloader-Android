import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import {
  ANDROID_OBB_SIDECAR_PATH,
  buildAndroidGameDownloadPlan,
  classifyAndroidPackageFile,
  githubGameReleasesUrl,
  parseAndroidObbSidecar,
  toAndroidDownloadFile,
  type AndroidGamePackage,
} from "../src/services/gamePackageUpdate";

const sha = (value: string) => createHash("sha256").update(value).digest("hex");

const APK = "CrossingVoid-Android-Shipping-arm64.apk";
const PAK = "CrossingVoid/Content/Paks/pakchunk0-Android.ucas";
const ENGINE = "Engine/Content/Renderer/TessellationTable.bin";
const VERSION_MARKER = "CrossingVoid.version.json";

function file(path: string, content: string) {
  return { path, sizeBytes: Buffer.byteLength(content, "utf8"), sha256: sha(content) };
}

function packageFixture(): AndroidGamePackage {
  return {
    version: "0.5.14",
    channel: "stable",
    productKey: "crossingvoid-android-game",
    baseUrl: "https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/",
    githubTag: "Android-V0.5.14",
    obb: {
      obbFileName: "main.1.com.TFAC.CorssingVoid.obb",
      entries: [PAK, ENGINE],
      version: "0.5.14",
      productKey: "crossingvoid-android-game",
    },
    files: [
      file(APK, "apk"),
      file(PAK, "pak-0.5.14"),
      file(ENGINE, "tessellation"),
      file(VERSION_MARKER, '{"version":"0.5.14"}'),
      file(ANDROID_OBB_SIDECAR_PATH, '{"obbFileName":"main.1.com.TFAC.CorssingVoid.obb"}'),
    ],
  };
}

describe("dual source manifest", () => {
  it("points the release list at the game repository", () => {
    expect(githubGameReleasesUrl()).toBe(
      "https://api.github.com/repos/kirito0000001/CrossingVoid/releases?per_page=10",
    );
  });

  it("uses the tag the manifest was actually read from", () => {
    // 清单是从 GitHub 读来的（下载站挂了）时，文件地址必须跟着那条 release 的标签走。
    const pkg = { ...packageFixture(), githubTag: "Android-V0.5.99" };
    const plan = buildAndroidGameDownloadPlan(pkg, null, { source: "github" });
    const pak = plan.files.find((entry) => entry.path === PAK);
    expect(pak?.urls[0]).toContain("releases/download/Android-V0.5.99/");
  });
});

describe("android obb sidecar", () => {
  it("parses the sidecar the packaging pipeline writes", () => {
    const sidecar = parseAndroidObbSidecar({
      obbFileName: "main.1.com.TFAC.CorssingVoid.obb",
      entries: [PAK, ENGINE],
      version: "0.5.14",
      productKey: "crossingvoid-android-game",
    });
    expect(sidecar.obbFileName).toBe("main.1.com.TFAC.CorssingVoid.obb");
    expect(sidecar.entries).toEqual([PAK, ENGINE]);
  });

  it("rejects an unusable sidecar instead of guessing", () => {
    expect(() => parseAndroidObbSidecar({ obbFileName: "", entries: [PAK] })).toThrow();
    expect(() =>
      parseAndroidObbSidecar({ obbFileName: "main.1.com.X.obb", entries: [] }),
    ).toThrow();
    expect(() =>
      parseAndroidObbSidecar({ obbFileName: "CrossingVoid.obb", entries: [PAK] }),
    ).toThrow(/规范/);
  });
});

describe("android package file kinds", () => {
  it("classifies apk / obb 条目 / 元数据 / 旁车", () => {
    const obb = { obbFileName: "main.1.com.TFAC.CorssingVoid.obb", entries: [PAK], version: "", productKey: "" };
    expect(classifyAndroidPackageFile(APK, obb)).toBe("apk");
    expect(classifyAndroidPackageFile(PAK, obb)).toBe("obb-entry");
    expect(classifyAndroidPackageFile(VERSION_MARKER, obb)).toBe("metadata");
    expect(classifyAndroidPackageFile(ANDROID_OBB_SIDECAR_PATH, obb)).toBe("obb-sidecar");
  });

  it("builds download urls by appending the relative path to baseUrl", () => {
    const pkg = packageFixture();
    const download = toAndroidDownloadFile(pkg.baseUrl, pkg.files[1], pkg.obb);
    expect(download.url).toBe(`${pkg.baseUrl}${PAK}`);
    expect(download.kind).toBe("obb-entry");
  });

  it("carries both sources per file, ordered by the preferred one", () => {
    const pkg = packageFixture();

    const officialFirst = buildAndroidGameDownloadPlan(pkg, null, { source: "official" });
    const pak = officialFirst.files.find((entry) => entry.path === PAK);
    expect(pak?.urls[0]).toBe(`${pkg.baseUrl}${PAK}`);
    // GitHub 备用源：附件名就是把路径里的 `/` 换成 `__`。
    expect(pak?.urls[1]).toBe(
      `https://github.com/kirito0000001/CrossingVoid/releases/download/Android-V0.5.14/${PAK.replace(/\//g, "__")}`,
    );
    expect(pak?.url).toBe(pak?.urls[0]);

    const githubFirst = buildAndroidGameDownloadPlan(pkg, null, { source: "github" });
    const githubPak = githubFirst.files.find((entry) => entry.path === PAK);
    expect(githubPak?.urls[0]).toContain("releases/download/Android-V0.5.14/");
    expect(githubPak?.urls[1]).toBe(`${pkg.baseUrl}${PAK}`);
  });

  it("drops channels that the remote switch closed", () => {
    const plan = buildAndroidGameDownloadPlan(packageFixture(), null, {
      source: "github",
      githubEnabled: false,
    });
    const pak = plan.files.find((entry) => entry.path === PAK);
    expect(pak?.urls).toEqual([`${packageFixture().baseUrl}${PAK}`]);
  });
});

describe("android download plan", () => {
  it("downloads everything and rebuilds the OBB on a fresh install", () => {
    const plan = buildAndroidGameDownloadPlan(packageFixture(), null);
    expect(plan.files).toHaveLength(5);
    expect(plan.totalBytes).toBe(plan.files.reduce((sum, file) => sum + file.sizeBytes, 0));
    expect(plan.apk?.path).toBe(APK);
    expect(plan.needsObbRebuild).toBe(true);
  });

  it("downloads only the changed pak and skips the OBB rebuild when nothing else changed", () => {
    const pkg = packageFixture();
    const local = {
      schemaVersion: 1 as const,
      productKey: pkg.productKey,
      version: pkg.version,
      files: pkg.files.map((entry) =>
        entry.path === PAK ? { ...entry, sha256: "b".repeat(64) } : entry,
      ),
    };
    const plan = buildAndroidGameDownloadPlan(pkg, local);
    expect(plan.files.map((entry) => entry.path)).toEqual([PAK]);
    expect(plan.needsObbRebuild).toBe(false);
  });

  it("rebuilds the OBB when a non-pak entry changed", () => {
    const pkg = packageFixture();
    const local = {
      schemaVersion: 1 as const,
      productKey: pkg.productKey,
      version: pkg.version,
      files: pkg.files.map((entry) =>
        entry.path === ENGINE ? { ...entry, sha256: "c".repeat(64) } : entry,
      ),
    };
    const plan = buildAndroidGameDownloadPlan(pkg, local);
    expect(plan.files.map((entry) => entry.path)).toEqual([ENGINE]);
    expect(plan.needsObbRebuild).toBe(true);
  });

  it("prunes files that the new manifest no longer lists", () => {
    const pkg = packageFixture();
    const stale = "CrossingVoid/Content/Paks/pakchunk9-Android.ucas";
    const local = {
      schemaVersion: 1 as const,
      productKey: pkg.productKey,
      version: pkg.version,
      files: [...pkg.files, file(stale, "old")],
    };
    const plan = buildAndroidGameDownloadPlan(pkg, local);
    expect(plan.prune).toEqual([stale]);
    expect(plan.files).toEqual([]);
  });

  it("keeps the sidecar and version marker as ordinary downloaded files", () => {
    const plan = buildAndroidGameDownloadPlan(packageFixture(), null);
    const kinds = new Map(plan.files.map((entry) => [entry.path, entry.kind]));
    expect(kinds.get(VERSION_MARKER)).toBe("metadata");
    expect(kinds.get(ANDROID_OBB_SIDECAR_PATH)).toBe("obb-sidecar");
  });

  it("always carries the download source the native side reads by name", () => {
    // 原生 GamePackagePlan 是**照字段名读**的：少一个字段整单解析就会抛异常。
    // 2026-09-22 安卓端"能检测到版本、一点下载就弹重新检测"就是这个 ——
    // 原生报了 `No value for source`，而当时 plan 里根本没有这个字段。
    // 它只是用来在界面上显示"下载源：github"，不参与下载逻辑，但**必须一直带着**。
    expect(buildAndroidGameDownloadPlan(packageFixture(), null).source).toBe("official");
    expect(
      buildAndroidGameDownloadPlan(packageFixture(), null, { source: "github" }).source,
    ).toBe("github");
  });

  it("keeps the native plan parser tolerant about the display-only source", () => {
    const planSource = readFileSync(
      resolve(
        process.cwd(),
        "android/app/src/main/java/com/lingjing/launcher/android/GamePackagePlan.java",
      ),
      "utf8",
    );
    const serviceSource = readFileSync(
      resolve(
        process.cwd(),
        "android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java",
      ),
      "utf8",
    );

    // 双保险：即使前端将来又漏了这个字段，原生也该用 optString 兜住，
    // 而不是让整单下载因为一个"只用来显示的字段"炸掉。
    expect(planSource).toContain('source.optString("source", "")');
    expect(planSource).not.toContain('source.getString("source")');

    // 还有一层：恢复任务前先验一遍计划，解析不了的旧计划要丢掉 ——
    // 否则那个 error 状态会把界面钉死在"重新检测"上（2026-09-22 的现场）。
    expect(serviceSource).toContain("if (!isRecoverablePlan(planJson))");
    expect(serviceSource).toContain("private static boolean isRecoverablePlan(String planJson)");
    expect(serviceSource).toContain("remove(LauncherStorage.PREF_PLAN)");
  });

  it("recovers the UI from a stale error state left behind by an older build", () => {
    const pluginSource = readFileSync(
      resolve(
        process.cwd(),
        "android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java",
      ),
      "utf8",
    );

    // 服务没在跑、状态却停在 error —— 那是一次**已经结束**的失败（而且多半是上一版留下的）。
    // 必须归位成 idle：否则界面每次启动都读到这个错，主按钮变成"重新检测"，
    // 玩家反而点不到"下载游戏"（2026-09-22 用户升到 1.4.4 后还卡在这个老错误上）。
    expect(pluginSource).toContain('!GameDownloadService.isRunning() && status.equals("error")');
    expect(pluginSource).toContain(
      "DownloadStateStore.writeStateAndBroadcast(getContext(), DownloadStateStore.idleState())",
    );
  });

  it("keeps the primary action steady while a download verifies each file", () => {
    const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");

    // 下载途中原生会为**每一个文件**推一次 `verifying`（下完立刻算 sha256），
    // 紧接着又推 `downloading`。那属于下载本身、不是独立阶段 ——
    // 照单全收地重算 phase 会让主按钮每文件在"暂停下载 / 校验中"之间抖一次
    // （2026-09-22 用户报的现场）。所以已经在 downloading 时要稳住。
    expect(appSource).toContain(
      'const downloadingAlready = phase.value === "downloading" && state.status === "verifying"',
    );
    expect(appSource).toContain("if (!downloadingAlready) {");
  });
});
