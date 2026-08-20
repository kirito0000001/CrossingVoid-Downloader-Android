# Android Launcher Main-Screen Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Android launcher's blocking update overlay and present launcher checking, downloading, verification, and installation through the existing home action and global progress dock.

**Architecture:** Keep the existing launcher update phases and native `LauncherUpdateService`; only change Vue presentation and access gating. `launcherAccessLocked` remains the network-game-download safety decision, while the main page and global task dock become the only launcher update UI.

**Tech Stack:** Vue 3, TypeScript, Vitest, Vite, Capacitor Android, Gradle/JUnit

---

### Task 1: Lock the main-screen update behavior with tests

**Files:**
- Modify: `tests/launcherUpdateIntegration.test.ts`

- [ ] **Step 1: Replace overlay assertions with failing main-screen assertions**

Assert that `App.vue` does not contain `launcher-update-mask` or `launcher-update-panel`, that the home primary action still handles `launcherUpdateReady` and `launcherUpdateInstall`, and that `showGlobalProgress` includes `launcherUpdating`.

```ts
it("keeps mandatory launcher updates on the main screen without a blocking overlay", () => {
  expect(appSource).not.toContain('class="launcher-update-mask"');
  expect(appSource).not.toContain('class="launcher-update-panel"');
  expect(appSource).toContain('case "launcherUpdateReady"');
  expect(appSource).toContain('case "launcherUpdateInstall"');
  expect(appSource).toContain('if (phase.value === "launcherUpdateReady")');
  expect(appSource).toContain('if (phase.value === "launcherUpdateInstall")');
});

it("uses the global progress dock while downloading a launcher update", () => {
  const progressSource = appSource.slice(
    appSource.indexOf("const showGlobalProgress"),
    appSource.indexOf("const progressAnimating"),
  );
  expect(progressSource).toContain('"launcherUpdating"');
  expect(appSource).toContain('v-if="showGlobalProgress"');
  expect(appSource).toContain('{{ Math.round(progress) }}%');
});
```

- [ ] **Step 2: Verify the new test fails for the current overlay**

Run: `npm.cmd test -- tests/launcherUpdateIntegration.test.ts`

Expected: FAIL because `App.vue` still contains `launcher-update-mask` and `launcher-update-panel`.

### Task 2: Remove the overlay and keep the safety gate

**Files:**
- Modify: `src/App.vue`
- Modify: `src/style.css`
- Test: `tests/launcherUpdateIntegration.test.ts`

- [ ] **Step 1: Remove only the blocking template and its private styles**

Delete the `<section v-if="launcherAccessLocked" class="launcher-update-mask">` template block and the `.launcher-update-mask` / `.launcher-update-panel` CSS rules. Do not remove `launcherAccessLocked`, `ensureLatestLauncherForNetworkDownload`, launcher update phases, native progress listeners, or install actions.

- [ ] **Step 2: Keep update errors actionable from the main button**

Retain the existing `handlePrimaryAction()` behavior that retries `refreshAllStatus()` when `launcherUpdateCheckError` is present. Keep `actionText`, `statusTitle`, `targetVersionLabel`, and the home warning bound to launcher phases.

- [ ] **Step 3: Run focused tests**

Run: `npm.cmd test -- tests/launcherUpdateIntegration.test.ts tests/launcherUpdate.test.ts`

Expected: both test files pass.

- [ ] **Step 4: Run full verification**

Run: `npm.cmd test`

Expected: all Vitest tests pass.

Run: `npm.cmd run build`

Expected: Vue TypeScript checking and Vite production build pass.

Run: `android\gradlew.bat -p android testDebugUnitTest`

Expected: Gradle Android unit tests pass without rebuilding Unreal.

- [ ] **Step 5: Review the scoped diff**

Run: `git diff --check -- src/App.vue src/style.css tests/launcherUpdateIntegration.test.ts`

Expected: no whitespace errors; the diff contains only the overlay removal and updated integration expectations.
