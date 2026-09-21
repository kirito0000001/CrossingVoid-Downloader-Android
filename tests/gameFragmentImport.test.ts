import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { nativeSource, readNativeSource } from "./helpers/androidNativeSources";

const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");
const bridgeSource = readFileSync(resolve(process.cwd(), "src/services/androidLauncher.ts"), "utf8");

describe("game fragment import", () => {
  it("builds the one plan that download and import share", () => {
    const importSource = appSource.slice(
      appSource.indexOf("async function importGameChunksFromDevice"),
      appSource.indexOf("async function exportGameChunksFromDevice"),
    );

    // 清单必须和下载那条链路是同一个构造：原生侧靠它把碎片对到一样的落点，
    // 否则"导入进来的文件"和"下载下来的文件"会分叉成两套位置。
    expect(importSource).toContain("buildAndroidGameDownloadPlan(updateInfo.value, localState");
    expect(importSource).toContain("await importGameChunks(plan)");
    // 没拿到清单就说清楚，别让玩家点了没反应
    expect(importSource).toContain("还没拿到游戏清单");
  });

  it("bridges the newer file-level plan as well as the legacy chunk plan", () => {
    expect(bridgeSource).toContain(
      "importGameChunks(options: { plan: AndroidGameDownloadPlan | AndroidDownloadPlan }): Promise<{ started: boolean }>;",
    );
    expect(bridgeSource).toContain(
      "export async function importGameChunks(plan: AndroidGameDownloadPlan | AndroidDownloadPlan)",
    );
  });

  it("matches fragments by manifest path so archives may be renamed", () => {
    const importer = readNativeSource("PackageFragmentImporter.java");

    // 压缩包里的条目名就是清单里的相对路径 —— 包的**文件名**随便改都能导
    expect(importer).toContain("private static String normalizePath(String raw)");
    expect(importer).toContain("plan.manifestFiles");
    expect(importer).toContain("manifest.get(key)");
    expect(importer).toContain("new ZipInputStream(");
    // 散件按扫描出的相对路径对上清单
    expect(importer).toContain("scanLooseFiles(root, \"\", archives, loose, host)");
    expect(importer).toContain("loose.put(normalizePath(prefix + name), child)");
  });

  it("writes through a temporary file and only keeps it after the hash matches", () => {
    const importer = readNativeSource("PackageFragmentImporter.java");

    expect(importer).toContain("temporaryFor(target)");
    expect(importer).toContain("MessageDigest.getInstance(\"SHA-256\")");
    expect(importer).toContain(".importing");
    // 对不上就删掉临时文件、记一笔 mismatched，正式位置一个字节都不留
    expect(importer).toContain("result.mismatched.add(entry.path)");
    expect(importer).toContain("result.missing.add(item.getValue().path)");
  });

  it("skips files that already match the manifest so importing twice is cheap", () => {
    const importer = readNativeSource("PackageFragmentImporter.java");

    expect(importer).toContain("private static boolean isAlreadyThere(File target, GamePackagePlan.FileEntry entry)");
    expect(importer).toContain("DownloadFileUtils.hashMatches(target, entry.sha256)");
    expect(importer).toContain("Set<String> handled = new HashSet<>()");
  });

  it("lands fragments exactly where the download path lands them", () => {
    // 落点必须复用 packageFileTarget：APK → prepared、OBB 条目 → entries 树下、其余 → prepared 平铺。
    // 自己再写一份落点判断，OBB 组装那段就会和下载分叉。
    expect(nativeSource).toContain("runPackageImport(String planJson, Uri treeUri, int startId)");
    expect(nativeSource).toContain("return packageFileTarget(entry, entriesDir, apkTarget, preparedDir);");
    expect(nativeSource).toContain("preparePackageArtifacts(entriesDir, apkTarget, obbTarget)");
    expect(nativeSource).toContain("private static boolean looksLikePackagePlan(String planJson)");
    expect(nativeSource).toContain("new JSONObject(planJson).has(\"files\")");
  });

  it("explains what is still missing instead of failing silently", () => {
    expect(nativeSource).toContain("private static String describeImportGaps(PackageFragmentImporter.Result result)");
    expect(nativeSource).toContain("碎片还不齐：");
    // 导不齐就停在 paused，等玩家补齐再点一次
    expect(nativeSource).toContain("if (!result.isComplete()) {");
  });
});
