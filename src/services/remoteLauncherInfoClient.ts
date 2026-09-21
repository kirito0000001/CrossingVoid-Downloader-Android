import { CapacitorHttp } from "@capacitor/core";

import {
  DOWNLOAD_CHANNELS_URL,
  LAUNCHER_NOTICE_URL,
  parseRemoteDownloadChannels,
  parseRemoteLauncherNotice,
  type RemoteDownloadChannels,
  type RemoteLauncherNotice,
} from "./remoteLauncherInfo";

/**
 * 安卓侧的"接收层"：只负责把 PC 开发页发布的两份文档拉下来，
 * 解析、判合法、落到渠道表这些事全部交给 `remoteLauncherInfo` 那份共用内核。
 *
 * 安卓不做自己的发布入口 —— 公告和渠道开关永远从 PC 开发页发布一次，
 * 两端读同一份文档，行为因此保持一致。
 */

async function fetchJson(url: string): Promise<unknown> {
  const response = await CapacitorHttp.get({
    url: `${url}?t=${Date.now()}`,
    headers: { "Cache-Control": "no-cache" },
    responseType: "text",
  });
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`启动器信息服务返回 HTTP ${response.status}。`);
  }
  const data = response.data;
  if (typeof data !== "string") return data;
  try {
    return JSON.parse(data.trim());
  } catch {
    throw new Error("启动器信息服务返回的内容不是合法 JSON。");
  }
}

/** 公告格式不对就抛错；调用方按"没有公告"处理。 */
export async function fetchAndroidLauncherNotice(): Promise<RemoteLauncherNotice> {
  const notice = parseRemoteLauncherNotice(await fetchJson(LAUNCHER_NOTICE_URL));
  if (!notice) throw new Error("远程公告格式不正确");
  return notice;
}

/**
 * 渠道文档还没发布（服务器上就是 404）或内容不合法时返回 null，
 * 调用方按"全部开放"处理：不能因为文档缺失把玩家挡在门外。
 */
export async function fetchAndroidDownloadChannels(): Promise<RemoteDownloadChannels | null> {
  try {
    return parseRemoteDownloadChannels(await fetchJson(DOWNLOAD_CHANNELS_URL));
  } catch (error) {
    console.warn("Unable to load remote download channels", error);
    return null;
  }
}
