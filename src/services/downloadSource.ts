import type { AndroidDownloadSource } from "./downloadPlan";

export const ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY = "crossing-void.android-launcher.download-source";

export function normalizeAndroidDownloadSource(value: unknown): AndroidDownloadSource {
  return value === "official" ? "official" : "github";
}

export function readAndroidDownloadSource(): AndroidDownloadSource {
  if (typeof window === "undefined") return "github";
  return normalizeAndroidDownloadSource(window.localStorage.getItem(ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY));
}

export function saveAndroidDownloadSource(source: AndroidDownloadSource) {
  if (typeof window !== "undefined") {
    window.localStorage.setItem(ANDROID_DOWNLOAD_SOURCE_STORAGE_KEY, normalizeAndroidDownloadSource(source));
  }
}
