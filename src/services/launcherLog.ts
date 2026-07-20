import { appendLauncherLog } from "./androidLauncher";

export type LauncherLogLevel = "debug" | "info" | "warning" | "error";

let writeChain = Promise.resolve();
let handlersInstalled = false;

export function redactSensitiveText(value: string) {
  return value
    .replace(/([?&](?:access_token|token|signature|security-token|x-oss-signature)=)[^&\s"']+/gi, "$1[redacted]")
    .replace(/("(?:accessToken|access_token|token|signature|password|authorization)"\s*:\s*")[^"]+/gi, "$1[redacted]")
    .slice(0, 20000);
}

function stringify(value: unknown) {
  if (value === undefined || value === null) return "";
  if (value instanceof Error) {
    return redactSensitiveText(JSON.stringify({ name: value.name, message: value.message, stack: value.stack || "" }));
  }
  try {
    return redactSensitiveText(typeof value === "string" ? value : JSON.stringify(value));
  } catch {
    return redactSensitiveText(String(value));
  }
}

export function writeLauncherLog(
  level: LauncherLogLevel,
  event: string,
  message: string,
  details?: unknown,
) {
  const operation = async () => {
    try {
      await appendLauncherLog({
        level,
        event: redactSensitiveText(event),
        message: redactSensitiveText(message),
        details: stringify(details),
      });
    } catch {
      // Local diagnostics must never block launcher behavior.
    }
  };
  writeChain = writeChain.then(operation, operation);
  return writeChain;
}

function clickedControl(event: MouseEvent) {
  const element = event.target instanceof Element ? event.target.closest("button, a, [role='button']") : null;
  if (!element) return;
  const label = element.getAttribute("aria-label") || element.getAttribute("title") || element.textContent || element.tagName;
  void writeLauncherLog("info", "ui.click", label.trim().replace(/\s+/g, " ").slice(0, 160), {
    tag: element.tagName,
    disabled: element instanceof HTMLButtonElement ? element.disabled : false,
  });
}

export function installGlobalLauncherLogHandlers() {
  if (handlersInstalled || typeof window === "undefined") return;
  handlersInstalled = true;

  window.addEventListener("error", (event) => {
    void writeLauncherLog("error", "window.error", event.message || "JavaScript error", {
      fileName: event.filename,
      line: event.lineno,
      column: event.colno,
      error: stringify(event.error),
    });
  });
  window.addEventListener("unhandledrejection", (event) => {
    void writeLauncherLog("error", "window.unhandledrejection", "Unhandled promise rejection", event.reason);
  });
  document.addEventListener("click", clickedControl, true);
  document.addEventListener("visibilitychange", () => {
    void writeLauncherLog("info", "app.visibility", document.visibilityState);
  });
  window.addEventListener("pagehide", () => {
    void writeLauncherLog("info", "app.pagehide", "Launcher page hidden");
  });
  void writeLauncherLog("info", "app.bootstrap", "Launcher JavaScript started", {
    userAgent: navigator.userAgent,
  });
}
