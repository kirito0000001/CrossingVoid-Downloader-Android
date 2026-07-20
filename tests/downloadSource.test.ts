import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import {
  ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY,
  normalizeAndroidDownloadSource,
  readAndroidDownloadSource,
  saveAndroidDownloadSource,
} from "../src/services/downloadSource";

const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");

describe("Android download source preference", () => {
  it("accepts OSS and Github while defaulting unknown values to Github", () => {
    expect(normalizeAndroidDownloadSource("official")).toBe("official");
    expect(normalizeAndroidDownloadSource("github")).toBe("github");
    expect(normalizeAndroidDownloadSource(null)).toBe("github");
    expect(normalizeAndroidDownloadSource("gitee")).toBe("github");
  });

  it("persists the selected source without replacing it with an active task source", () => {
    const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
    const values = new Map<string, string>();
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: {
        localStorage: {
          getItem: (key: string) => values.get(key) ?? null,
          setItem: (key: string, value: string) => values.set(key, value),
        },
      },
    });

    try {
      saveAndroidDownloadSource("official");
      expect(values.get(ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY)).toBe("official");
      expect(readAndroidDownloadSource()).toBe("official");
      saveAndroidDownloadSource("github");
      expect(values.get(ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY)).toBe("github");
      expect(readAndroidDownloadSource()).toBe("github");
    } finally {
      if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow);
      else Reflect.deleteProperty(globalThis, "window");
    }

    expect(appSource).not.toContain("downloadSource.value = nativeDownload.source");
    expect(appSource).toContain("activeDownloadSource.value = state.source");
    expect(appSource).not.toContain('class="source-disabled-note">暂时关闭</span>');
    expect(appSource).toContain('@click="downloadSource = \'official\'"');
  });

  it("blocks or pauses OSS downloads when the server drops below its 3 GB threshold", () => {
    expect(appSource).toContain("officialTrafficBlocked");
    expect(appSource).toContain("ensureOfficialTrafficAvailable");
    expect(appSource).toContain("trafficQuota.value?.downloadAllowed");
    expect(appSource).toContain("pauseOfficialSourceForLowTraffic");
    expect(appSource).toContain("5 * 60 * 1000");
    expect(appSource).toContain("服务器当前流量不足，请更换下载源。");
  });
});
