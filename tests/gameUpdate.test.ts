import { describe, expect, it } from "vitest";
import {
  ANDROID_GAME_METADATA_MANIFEST_URL,
  isVersionAtLeast,
  parseAndroidUpdateCheckResponse,
} from "../src/services/gameUpdate";

describe("Android game update manifest", () => {
  it("uses the single current Gitee manifest contract", () => {
    const update = parseAndroidUpdateCheckResponse({
      schemaVersion: 2,
      productKey: "crossingvoid-android-game",
      downloadReleaseTag: "Android-V0.5.14",
      latest: {
        version: "V0.5.14",
        channel: "stable",
        assets: [{
          runtime: "Android",
          fileName: "CrossingVoid-Android-Package.zip",
          objectKey: "Akege304/CrossingVoid/Android/package.zip",
          sha256: "a".repeat(64),
          sizeBytes: 524_288_000,
          chunks: [{
            index: 1,
            count: 1,
            fileName: "CrossingVoid手机端.碎片001",
            githubFileName: "CrossingVoid手机端.碎片001",
            objectKey: "chunks/CrossingVoid手机端.碎片001",
            sha256: "b".repeat(64),
            sizeBytes: 524_288_000,
          }],
        }],
      },
    });

    expect(update.version).toBe("V0.5.14");
    expect(update.hasUpdate).toBe(true);
    expect(update.downloadReleaseTag).toBe("Android-V0.5.14");
    expect(update.asset.runtime).toBe("Android");
    expect(update.asset.chunks[0]?.githubFileName).toBe("CrossingVoid手机端.碎片001");
    expect(update.asset.chunks).toHaveLength(1);
  });

  it("rejects the removed backend response shape", () => {
    expect(() =>
      parseAndroidUpdateCheckResponse({ success: false, message: "没有找到 Android 更新清单" }),
    ).toThrow("Android 游戏清单格式不受支持");
  });

  it("accepts the Gitee-published game manifest without calling the OSS update API", () => {
    const update = parseAndroidUpdateCheckResponse({
      schemaVersion: 2,
      productKey: "crossingvoid-android-game",
      downloadReleaseTag: "Android-V0.5.12",
      latest: {
        version: "V0.5.12",
        channel: "stable",
        assets: [
          {
            runtime: "Android",
            fileName: "CrossingVoid-Android-Package.zip",
            objectKey: "channels/stable/Android/package.zip",
            sha256: "a".repeat(64),
            sizeBytes: 128,
            chunks: [{
              index: 1,
              count: 1,
              fileName: "CrossingVoid手机端.碎片001",
              githubFileName: "CrossingVoid手机端.碎片001",
              objectKey: "channels/stable/Android/chunks/part001",
              sha256: "b".repeat(64),
              sizeBytes: 128,
            }],
          },
        ],
      },
    });

    expect(ANDROID_GAME_METADATA_MANIFEST_URL).toBe(
      "https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android/raw/master/game/android-latest.json",
    );
    expect(update.version).toBe("V0.5.12");
    expect(update.asset.chunks[0]?.fileName).toBe("CrossingVoid手机端.碎片001");
  });

  it("parses the text response returned by Gitee Raw on Android", () => {
    const update = parseAndroidUpdateCheckResponse(JSON.stringify({
      schemaVersion: 2,
      productKey: "crossingvoid-android-game",
      downloadReleaseTag: "Android-V0.5.12",
      latest: {
        version: "V0.5.12",
        channel: "stable",
        assets: [{
          runtime: "Android",
          fileName: "CrossingVoid-Android-Package.zip",
          objectKey: "channels/stable/Android/package.zip",
          sha256: "a".repeat(64),
          sizeBytes: 128,
          chunks: [{
            index: 1,
            count: 1,
            fileName: "CrossingVoid手机端.碎片001",
            githubFileName: "CrossingVoid.001",
            objectKey: "channels/stable/Android/chunks/part001",
            sha256: "b".repeat(64),
            sizeBytes: 128,
          }],
        }],
      },
    }));

    expect(update.version).toBe("V0.5.12");
  });

  it("accepts the numeric filename used by an existing Github release asset", () => {
    const update = parseAndroidUpdateCheckResponse({
      schemaVersion: 2,
      productKey: "crossingvoid-android-game",
      downloadReleaseTag: "Android-V0.5.12",
      latest: {
        version: "V0.5.12",
        channel: "stable",
        assets: [{
          runtime: "Android",
          fileName: "CrossingVoid-Android-Package.zip",
          objectKey: "package.zip",
          sha256: "a".repeat(64),
          sizeBytes: 128,
          chunks: [{
            index: 1,
            count: 1,
            fileName: "CrossingVoid手机端.碎片001",
            githubFileName: "CrossingVoid.001",
            objectKey: "chunks/001",
            sha256: "b".repeat(64),
            sizeBytes: 128,
          }],
        }],
      },
    });

    expect(update.asset.chunks[0]?.githubFileName).toBe("CrossingVoid.001");
  });

  it("rejects legacy names and incomplete integrity metadata", () => {
    const base = {
      schemaVersion: 2,
      productKey: "crossingvoid-android-game",
      downloadReleaseTag: "Android-V0.5.12",
      latest: {
        version: "V0.5.12",
        channel: "stable",
        assets: [{
          runtime: "Android",
          fileName: "CrossingVoid-Android-Package.zip",
          objectKey: "package.zip",
          sha256: "a".repeat(64),
          sizeBytes: 128,
          chunks: [{
            index: 1,
            count: 1,
            fileName: "CrossingVoid.001",
            githubFileName: "CrossingVoid.001",
            objectKey: "chunks/001",
            sha256: "b".repeat(64),
            sizeBytes: 128,
          }],
        }],
      },
    };
    expect(() => parseAndroidUpdateCheckResponse(base)).toThrow("标准文件名");

    const missingHash = structuredClone(base);
    missingHash.latest.assets[0].chunks[0].fileName = "CrossingVoid手机端.碎片001";
    missingHash.latest.assets[0].chunks[0].githubFileName = "CrossingVoid手机端.碎片001";
    missingHash.latest.assets[0].chunks[0].sha256 = "";
    expect(() => parseAndroidUpdateCheckResponse(missingHash)).toThrow("SHA256");
  });

  it("compares the installed version with a V-prefixed remote version", () => {
    expect(isVersionAtLeast("0.5.14", "V0.5.14")).toBe(true);
    expect(isVersionAtLeast("0.5.13", "V0.5.14")).toBe(false);
  });

  it("rejects the removed v1 game metadata contract", () => {
    expect(() => parseAndroidUpdateCheckResponse({
      schemaVersion: 1,
      productKey: "crossingvoid-android-game",
    })).toThrow("格式不受支持");
  });
});
