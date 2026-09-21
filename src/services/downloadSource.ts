import type { AndroidDownloadSource } from "./downloadPlan";

export const ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY = "crossing-void.android-launcher.download-source";

export function normalizeAndroidDownloadSource(value: unknown): AndroidDownloadSource {
  // 两个源都能用（GitHub Release 里同样按 `路径 → 附件名` 放了一份），默认走官方源。
  return value === "github" ? "github" : "official";
}

export function readAndroidDownloadSource(): AndroidDownloadSource {
  if (typeof window === "undefined") return "official";
  return normalizeAndroidDownloadSource(window.localStorage.getItem(ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY));
}

export function saveAndroidDownloadSource(source: AndroidDownloadSource) {
  if (typeof window !== "undefined") {
    window.localStorage.setItem(ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY, normalizeAndroidDownloadSource(source));
  }
}
