import { CapacitorHttp } from "@capacitor/core";

const TRAFFIC_STATUS_URL = "https://www.crossingvoid.top/api/toolbox-updates/traffic-status";

export type TrafficQuotaStatus = {
  success: boolean;
  available: boolean;
  downloadAllowed: boolean;
  isLow: boolean;
  totalBytes: number;
  remainingBytes: number;
  thresholdBytes: number;
  expiresAt?: string | null;
  updatedAt: string;
  packageCount: number;
  message: string;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" ? (value as Record<string, unknown>) : null;
}

function readNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

export function parseTrafficQuotaStatus(payload: unknown): TrafficQuotaStatus {
  const source = asRecord(payload);
  if (!source || source.success !== true) {
    throw new Error(typeof source?.message === "string" ? source.message : "服务器流量额度查询失败");
  }

  return {
    success: true,
    available: source.available === true,
    downloadAllowed: source.downloadAllowed !== false,
    isLow: source.isLow === true,
    totalBytes: readNumber(source.totalBytes),
    remainingBytes: readNumber(source.remainingBytes),
    thresholdBytes: readNumber(source.thresholdBytes),
    expiresAt: typeof source.expiresAt === "string" ? source.expiresAt : null,
    updatedAt: typeof source.updatedAt === "string" ? source.updatedAt : "",
    packageCount: readNumber(source.packageCount),
    message: typeof source.message === "string" ? source.message : "",
  };
}

export async function fetchTrafficQuotaStatus() {
  const response = await CapacitorHttp.get({ url: `${TRAFFIC_STATUS_URL}?t=${Date.now()}` });
  return parseTrafficQuotaStatus(response.data);
}
