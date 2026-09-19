const LAUNCHER_UPDATE_PHASES = new Set([
  "launcherChecking",
  "launcherUpdateReady",
  "launcherUpdating",
  "launcherUpdateInstall",
  "launcherInstalling",
]);

export function canUseAndroidLauncherNetwork(
  checkCompleted: boolean,
  checkError: string,
  phase: string,
) {
  return checkCompleted && !checkError && !LAUNCHER_UPDATE_PHASES.has(phase);
}
