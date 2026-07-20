import { describe, expect, it } from "vitest";
import { parseTrafficQuotaStatus } from "../src/services/trafficStatus";

describe("server traffic quota", () => {
  it("keeps the server-reported usable quota and expiry", () => {
    const quota = parseTrafficQuotaStatus({
      success: true,
      available: true,
      downloadAllowed: true,
      isLow: false,
      totalBytes: 214_748_364_800,
      remainingBytes: 95_158_274_282,
      thresholdBytes: 3_221_225_472,
      expiresAt: "2026-07-25T00:00:00+08:00",
      updatedAt: "2026-07-20T13:53:02+08:00",
      packageCount: 2,
      message: "流量额度查询成功。",
    });

    expect(quota.remainingBytes).toBe(95_158_274_282);
    expect(quota.totalBytes).toBe(214_748_364_800);
    expect(quota.expiresAt).toContain("2026-07-25");
  });

  it("rejects malformed or failed responses", () => {
    expect(() => parseTrafficQuotaStatus({ success: false, message: "暂不可用" })).toThrow("暂不可用");
  });
});
