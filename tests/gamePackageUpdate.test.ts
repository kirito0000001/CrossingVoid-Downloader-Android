import { createHash } from "node:crypto";
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
});
