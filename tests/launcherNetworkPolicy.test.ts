import { describe, expect, it } from "vitest";

import { canUseAndroidLauncherNetwork } from "../src/services/launcherNetworkPolicy";

describe("Android launcher network policy", () => {
  it("allows game network operations only after the current launcher is verified", () => {
    expect(canUseAndroidLauncherNetwork(true, "", "updateReady")).toBe(true);
    expect(canUseAndroidLauncherNetwork(false, "", "launcherChecking")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "HTTP 503", "error")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "", "launcherUpdateReady")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "", "launcherUpdating")).toBe(false);
  });
});
