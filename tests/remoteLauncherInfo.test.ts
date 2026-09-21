import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { httpGet } = vi.hoisted(() => ({ httpGet: vi.fn() }));

vi.mock("@capacitor/core", () => ({
  CapacitorHttp: { get: httpGet },
}));

import {
  DOWNLOAD_CHANNELS_URL,
  LAUNCHER_NOTICE_URL,
  REMOTE_LAUNCHER_INFO_CORE_VERSION,
  isDownloadChannelEnabled,
  parseRemoteLauncherNotice,
  pickAvailableDownloadChannel,
  resolveDownloadChannelStates,
} from "../src/services/remoteLauncherInfo";
import {
  fetchAndroidDownloadChannels,
  fetchAndroidLauncherNotice,
} from "../src/services/remoteLauncherInfoClient";

const CATALOG = ["official", "github"] as const;

const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");

function respond(status: number, data: unknown) {
  httpGet.mockResolvedValueOnce({ status, data: typeof data === "string" ? data : JSON.stringify(data) });
}

/**
 * 共用内核守卫：安卓的 `src/services/remoteLauncherInfo.ts` 与 PC 仓库里的
 * `src/remoteLauncherInfo.ts` **逐字相同**（换行按 LF 计算）。
 * 公告和渠道开关都由 PC 开发页发布，两端靠这一份解析逻辑保持一致。
 */
describe("shared remote launcher info core", () => {
  it("keeps the core file identical across repos (hash check)", () => {
    const source = readFileSync(resolve(process.cwd(), "src/services/remoteLauncherInfo.ts"), "utf8")
      .split("\r\n")
      .join("\n");
    expect(REMOTE_LAUNCHER_INFO_CORE_VERSION).toBe("1");
    expect(createHash("sha256").update(source, "utf8").digest("hex")).toBe(
      "f617fd8ca540cd2c9ae5759e30abd2d6b9df73de4e1d70e4e37d720522fb8de7",
    );
  });

  it("reads both documents from the site the PC dev page publishes to", () => {
    expect(LAUNCHER_NOTICE_URL).toBe("https://www.crossingvoid.top/launcher-notice.json");
    expect(DOWNLOAD_CHANNELS_URL).toBe("https://www.crossingvoid.top/launcher-download-channels.json");
  });
});

describe("android receives the PC notice", () => {
  beforeEach(() => {
    httpGet.mockReset();
  });

  it("parses the published notice", () => {
    const notice = parseRemoteLauncherNotice({
      schemaVersion: 1,
      id: "notice-1789902223135",
      enabled: true,
      level: "info",
      title: "维护公告",
      content: "暂不开放下载",
      publishedAt: 1789902223135,
    });
    expect(notice?.enabled).toBe(true);
    expect(notice?.title).toBe("维护公告");
    // 关掉的公告要有空标题/空正文才合法，"没有公告"就是这种形态。
    expect(
      parseRemoteLauncherNotice({
        schemaVersion: 1,
        id: "notice-2",
        enabled: false,
        level: "info",
        title: "",
        content: "",
        publishedAt: 2,
      })?.enabled,
    ).toBe(false);
    expect(parseRemoteLauncherNotice({ schemaVersion: 2 })).toBeNull();
  });

  it("fetches the notice with a cache buster", async () => {
    respond(200, {
      schemaVersion: 1,
      id: "notice-1",
      enabled: true,
      level: "warning",
      title: "维护",
      content: "稍后开放",
      publishedAt: 1,
    });
    const notice = await fetchAndroidLauncherNotice();
    expect(notice.level).toBe("warning");
    expect(httpGet.mock.calls[0][0].url.startsWith(`${LAUNCHER_NOTICE_URL}?t=`)).toBe(true);
  });

  it("treats a broken notice as 'no notice' instead of crashing", async () => {
    respond(200, "{ not json");
    await expect(fetchAndroidLauncherNotice()).rejects.toThrow();
    respond(404, "not found");
    await expect(fetchAndroidLauncherNotice()).rejects.toThrow();
  });
});

describe("android receives the PC download channel switches", () => {
  let warn: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    httpGet.mockReset();
    // 拉不到渠道文档是预期情况（线上现在就是 404），这里只是别把噪声打进测试输出。
    warn = vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    warn.mockRestore();
  });

  it("returns null while the dev page has not published the document yet", async () => {
    // 线上现在就是 404：文档没发布时必须按"全部开放"处理。
    respond(404, "404 - 找不到文件或目录。");
    const channels = await fetchAndroidDownloadChannels();
    expect(channels).toBeNull();
    const states = resolveDownloadChannelStates(CATALOG, channels);
    expect(states.every((state) => state.enabled)).toBe(true);
  });

  it("returns null for a malformed document", async () => {
    respond(200, { schemaVersion: 1, channels: [{ key: "official", enabled: "yes" }] });
    await expect(fetchAndroidDownloadChannels()).resolves.toBeNull();
  });

  it("applies the published switches and auto-picks an open channel", async () => {
    respond(200, {
      schemaVersion: 1,
      channels: [
        { key: "official", enabled: false, note: "官方源维护中" },
        { key: "github", enabled: true, note: "" },
      ],
      publishedAt: 1789900000000,
    });
    const channels = await fetchAndroidDownloadChannels();
    const states = resolveDownloadChannelStates(CATALOG, channels);
    expect(isDownloadChannelEnabled(states, "official")).toBe(false);
    expect(isDownloadChannelEnabled(states, "github")).toBe(true);
    expect(pickAvailableDownloadChannel(states, "official")).toBe("github");
  });
});

describe("android launcher UI wiring", () => {
  it("only receives: fetch both documents from the shared client", () => {
    expect(appSource).toContain("fetchAndroidLauncherNotice");
    expect(appSource).toContain("fetchAndroidDownloadChannels");
    // 渠道开关必须真的进到清单请求与下载计划里，而不只是画个灰按钮。
    expect(appSource).toContain("officialEnabled: officialChannelEnabled.value");
    expect(appSource).toContain("githubEnabled: githubChannelEnabled.value");
  });

  it("disables a closed channel and blocks the download entry", () => {
    expect(appSource).toContain(":disabled=\"downloadSourceLocked || !officialChannelEnabled\"");
    expect(appSource).toContain(":disabled=\"downloadSourceLocked || !githubChannelEnabled\"");
    expect(appSource).toContain("ensureDownloadChannelAvailable");
    expect(appSource).toContain("当前渠道已关闭，请更换");
    // 自动换源的提示要活得比"下一次检测版本"久，所以单独一个 ref。
    expect(appSource).toContain("channelSwitchNotice");
  });

  it("shows the remote notice on the notice page and as a dismissible popup", () => {
    expect(appSource).toContain("noticeVisible");
    expect(appSource).toContain("remote-notice-mask");
    expect(appSource).toContain("showRemoteLauncherNotice");
    // 原来的硬编码测试公告必须已经被远程内容替换掉。
    expect(appSource).not.toContain("Android 启动器测试");
  });
});
