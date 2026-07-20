import { describe, expect, it } from "vitest";
import {
  enqueueAndroidDiagnostic,
  flushAndroidDiagnostics,
  readQueuedAndroidDiagnostics,
  type DiagnosticStorage,
  type LauncherDiagnostic,
} from "../src/services/diagnostics";

function createStorage(): DiagnosticStorage {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

function diagnostic(stage: string): LauncherDiagnostic {
  return {
    stage,
    message: "test",
    launcherVersion: "1.0.16",
    gameVersion: "0.5.12",
    gameVersionCode: 512,
    gameLastUpdateTime: 1,
    targetVersion: "V0.5.14",
    phase: "readyInstall",
    nativeStatus: "ready",
    source: "official",
  };
}

describe("Android diagnostic queue", () => {
  it("keeps a diagnostic locally when delivery fails", async () => {
    const storage = createStorage();
    enqueueAndroidDiagnostic(diagnostic("install-action-start"), storage);

    await flushAndroidDiagnostics(storage, async () => {
      throw new Error("offline");
    });

    expect(readQueuedAndroidDiagnostics(storage)).toHaveLength(1);
  });

  it("retries queued diagnostics and removes only delivered entries", async () => {
    const storage = createStorage();
    enqueueAndroidDiagnostic(diagnostic("install-action-start"), storage);
    enqueueAndroidDiagnostic(diagnostic("install-resume"), storage);
    const delivered: string[] = [];

    await flushAndroidDiagnostics(storage, async (entry) => {
      delivered.push(entry.stage);
    });

    expect(delivered).toEqual(["install-action-start", "install-resume"]);
    expect(readQueuedAndroidDiagnostics(storage)).toEqual([]);
  });
});
