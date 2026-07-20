import { CapacitorHttp } from "@capacitor/core";

const DIAGNOSTICS_URL = "https://www.crossingvoid.top/api/launcher-diagnostics/report";
const DIAGNOSTICS_STORAGE_KEY = "crossing-void.android-launcher.pending-diagnostics";
const MAX_QUEUED_DIAGNOSTICS = 50;

export type LauncherDiagnostic = {
  stage: string;
  message: string;
  launcherVersion: string;
  gameVersion: string;
  gameVersionCode: number;
  gameLastUpdateTime: number;
  targetVersion: string;
  phase: string;
  nativeStatus: string;
  source: string;
};

export type QueuedLauncherDiagnostic = LauncherDiagnostic & {
  id: string;
  occurredAt: string;
};

export type DiagnosticStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;
export type DiagnosticSender = (diagnostic: QueuedLauncherDiagnostic) => Promise<void>;

function defaultStorage(): DiagnosticStorage | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function readQueuedAndroidDiagnostics(storage: DiagnosticStorage): QueuedLauncherDiagnostic[] {
  try {
    const raw = storage.getItem(DIAGNOSTICS_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((item) => item && typeof item.id === "string") : [];
  } catch {
    return [];
  }
}

function writeQueuedAndroidDiagnostics(storage: DiagnosticStorage, diagnostics: QueuedLauncherDiagnostic[]) {
  if (diagnostics.length === 0) {
    storage.removeItem(DIAGNOSTICS_STORAGE_KEY);
    return;
  }
  storage.setItem(DIAGNOSTICS_STORAGE_KEY, JSON.stringify(diagnostics.slice(-MAX_QUEUED_DIAGNOSTICS)));
}

export function enqueueAndroidDiagnostic(
  diagnostic: LauncherDiagnostic,
  storage: DiagnosticStorage,
): QueuedLauncherDiagnostic {
  const queued = {
    ...diagnostic,
    id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`,
    occurredAt: new Date().toISOString(),
  };
  writeQueuedAndroidDiagnostics(storage, [...readQueuedAndroidDiagnostics(storage), queued]);
  return queued;
}

async function sendAndroidDiagnostic(diagnostic: QueuedLauncherDiagnostic) {
  await CapacitorHttp.post({
    url: DIAGNOSTICS_URL,
    headers: { "Content-Type": "application/json" },
    data: {
      productKey: "crossingvoid-android-launcher",
      ...diagnostic,
    },
  });
}

export async function flushAndroidDiagnostics(
  storage: DiagnosticStorage | null = defaultStorage(),
  sender: DiagnosticSender = sendAndroidDiagnostic,
) {
  if (!storage) return;
  const pending = readQueuedAndroidDiagnostics(storage);
  for (const diagnostic of pending) {
    try {
      await sender(diagnostic);
    } catch {
      break;
    }
    const remaining = readQueuedAndroidDiagnostics(storage).filter((item) => item.id !== diagnostic.id);
    writeQueuedAndroidDiagnostics(storage, remaining);
  }
}

export async function reportAndroidDiagnostic(diagnostic: LauncherDiagnostic) {
  const storage = defaultStorage();
  if (!storage) {
    try {
      await sendAndroidDiagnostic({
        ...diagnostic,
        id: `${Date.now().toString(36)}-direct`,
        occurredAt: new Date().toISOString(),
      });
    } catch {
      // Diagnostics must never change the user's download or installation flow.
    }
    return;
  }

  enqueueAndroidDiagnostic(diagnostic, storage);
  await flushAndroidDiagnostics(storage);
}
