/**
 * 远程公告 + 远程下载渠道开关的解析内核，PC 与 Android **逐字共用**。
 *
 * 两份文档都由 PC 开发页发布到同一个静态站（`https://www.crossingvoid.top/`），
 * 发布一次两端同时生效 —— 安卓端只负责接收和展示，不做自己的发布入口。
 *
 * 这个文件必须在两个仓库里逐字相同（换行按 LF 计算）：
 * - PC：`src/remoteLauncherInfo.ts`
 * - Android：`src/services/remoteLauncherInfo.ts`
 * 两个仓库各有一条哈希守卫测试；改这里时两边一起改、一起更新哈希。
 */

export const REMOTE_LAUNCHER_INFO_CORE_VERSION = "1";

/** 远程公告：开发页发布 → 玩家侧弹窗 + 公告板。 */
export const LAUNCHER_NOTICE_URL = "https://www.crossingvoid.top/launcher-notice.json";

/**
 * 远程下载渠道开关。
 *
 * 契约是**数组**：每个渠道各自独立开关、各自带说明；以后新增渠道只要在启动器自己的
 * 渠道表里加一行，服务端文档跟着多一项即可。服务端没提到的渠道按"默认开放"处理，
 * 保证旧文档与旧启动器都能正常跑。
 */
export const DOWNLOAD_CHANNELS_URL = "https://www.crossingvoid.top/launcher-download-channels.json";

export type RemoteNoticeLevel = "info" | "warning" | "error";

export type RemoteLauncherNotice = {
  schemaVersion: 1;
  id: string;
  enabled: boolean;
  level: RemoteNoticeLevel;
  title: string;
  content: string;
  publishedAt: number;
};

export type RemoteDownloadChannel = {
  key: string;
  enabled: boolean;
  note: string;
};

export type RemoteDownloadChannels = {
  schemaVersion: 1;
  channels: RemoteDownloadChannel[];
  publishedAt: number;
};

export type DownloadChannelState<K extends string = string> = {
  key: K;
  enabled: boolean;
  /** 该渠道自己的关闭说明（整站级别的说明走远程公告，不在这里重复）。 */
  note: string;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function asTrimmedString(value: unknown, maxLength: number) {
  if (typeof value !== "string") return "";
  return value.trim().slice(0, maxLength);
}

/** 解析远端公告；任何一处不合法就整份判为无效（返回 null，按"没有公告"处理）。 */
export function parseRemoteLauncherNotice(payload: unknown): RemoteLauncherNotice | null {
  const notice = asRecord(payload);
  if (!notice || notice.schemaVersion !== 1) return null;
  if (typeof notice.id !== "string" || !notice.id.trim()) return null;
  if (typeof notice.enabled !== "boolean") return null;
  if (notice.level !== "info" && notice.level !== "warning" && notice.level !== "error") return null;
  if (typeof notice.title !== "string" || typeof notice.content !== "string") return null;
  if (typeof notice.publishedAt !== "number" || !Number.isFinite(notice.publishedAt)) return null;
  if (notice.enabled && (!notice.title.trim() || !notice.content.trim())) return null;
  return {
    schemaVersion: 1,
    id: notice.id.trim(),
    enabled: notice.enabled,
    level: notice.level,
    title: notice.title.trim(),
    content: notice.content.trim(),
    publishedAt: notice.publishedAt,
  };
}

/** 解析远端渠道文档；任何一项不合法就整份判为无效（返回 null，按"全部开放"处理）。 */
export function parseRemoteDownloadChannels(payload: unknown): RemoteDownloadChannels | null {
  const record = asRecord(payload);
  if (!record || record.schemaVersion !== 1) return null;
  const rawChannels = Array.isArray(record.channels) ? record.channels : null;
  if (!rawChannels || rawChannels.length === 0) return null;

  const channels: RemoteDownloadChannel[] = [];
  const seen = new Set<string>();
  for (const item of rawChannels) {
    const entry = asRecord(item);
    if (!entry) return null;
    const key = asTrimmedString(entry.key, 32).toLowerCase();
    if (!key || !/^[a-z0-9][a-z0-9._-]{0,31}$/.test(key)) return null;
    if (seen.has(key)) return null;
    if (typeof entry.enabled !== "boolean") return null;
    seen.add(key);
    channels.push({
      key,
      enabled: entry.enabled,
      note: asTrimmedString(entry.note, 200),
    });
  }

  const publishedAt = typeof record.publishedAt === "number" && Number.isFinite(record.publishedAt)
    ? record.publishedAt
    : 0;

  return {
    schemaVersion: 1,
    channels,
    publishedAt,
  };
}

/**
 * 把远端开关套到本地渠道表上。
 * 本地有、远端没提到的渠道 → 默认开放（新渠道上线时不会因为文档没更新而全员禁下载）。
 */
export function resolveDownloadChannelStates<K extends string>(
  catalogKeys: readonly K[],
  remote: RemoteDownloadChannels | null,
): DownloadChannelState<K>[] {
  const remoteByKey = new Map<string, RemoteDownloadChannel>();
  for (const channel of remote?.channels ?? []) {
    remoteByKey.set(channel.key, channel);
  }

  return catalogKeys.map((key) => {
    const matched = remoteByKey.get(key);
    return {
      key,
      enabled: matched ? matched.enabled : true,
      note: matched?.note ?? "",
    };
  });
}

export function downloadChannelState<K extends string>(
  states: readonly DownloadChannelState<K>[],
  key: K,
): DownloadChannelState<K> {
  return states.find((state) => state.key === key) ?? {
    key,
    enabled: true,
    note: "",
  };
}

export function isDownloadChannelEnabled<K extends string>(
  states: readonly DownloadChannelState<K>[],
  key: K,
) {
  return downloadChannelState(states, key).enabled;
}

/** 当前渠道被关掉时，找一个还能用的；都关了返回 null。 */
export function pickAvailableDownloadChannel<K extends string>(
  states: readonly DownloadChannelState<K>[],
  preferred: K,
): K | null {
  if (downloadChannelState(states, preferred).enabled) return preferred;
  const fallback = states.find((state) => state.enabled);
  return fallback ? fallback.key : null;
}

/**
 * 给玩家看的补充说明：只列**写明了关闭原因**的渠道。
 * "某个渠道被关"本身由当前渠道的提示负责（当前渠道已关闭，请更换），这里不重复。
 * 整站维护信息走远程公告。
 */
export function downloadChannelNotice<K extends string>(
  states: readonly DownloadChannelState<K>[],
  resolveName: (key: K) => string = (key) => key,
) {
  return states
    .filter((state) => !state.enabled && state.note)
    .map((state) => `${resolveName(state.key)}：${state.note}`)
    .join(" ");
}
