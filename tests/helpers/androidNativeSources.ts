import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const projectRoot = process.cwd();
const nativePackageDir = "android/app/src/main/java/com/lingjing/launcher/android";

export function readSource(relativePath: string) {
  return readFileSync(resolve(projectRoot, relativePath), "utf8");
}

export function readNativeSource(fileName: string) {
  return readSource(`${nativePackageDir}/${fileName}`);
}

/**
 * 原生层所有 Java 源文件的合并文本。
 *
 * 断言“某个能力存在”时用它：职责从 GameDownloadService 拆到新类之后，
 * 只要能力还在，测试就不该因为换了文件而失败。
 */
export const nativeSource = readdirSync(resolve(projectRoot, nativePackageDir))
  .filter((file) => file.endsWith(".java"))
  .map((file) => readFileSync(resolve(projectRoot, nativePackageDir, file), "utf8"))
  .join("\n");
