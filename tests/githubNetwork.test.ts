import { describe, expect, it } from "vitest";

import { githubNetworkWarning, type GithubNetworkStatus } from "../src/services/githubNetwork";

function status(overrides: Partial<GithubNetworkStatus> = {}): GithubNetworkStatus {
  return {
    proxyDetected: true,
    reachable: true,
    latencyMs: 120,
    ...overrides,
  };
}

describe("Github network guidance", () => {
  it("prioritizes the missing-proxy warning over latency", () => {
    expect(githubNetworkWarning(status({ proxyDetected: false, latencyMs: 9_999, reachable: false }))).toBe(
      "当前设备不存在网络代理，下载可能会超时",
    );
  });

  it("warns when a detected proxy has high latency or cannot reach Github", () => {
    expect(githubNetworkWarning(status({ latencyMs: 2_500 }))).toBe("当前网络不佳，请更换代理");
    expect(githubNetworkWarning(status({ reachable: false, latencyMs: null }))).toBe("当前网络不佳，请更换代理");
  });

  it("does not warn when Github is reachable through a responsive proxy", () => {
    expect(githubNetworkWarning(status({ latencyMs: 480 }))).toBe("");
  });
});
