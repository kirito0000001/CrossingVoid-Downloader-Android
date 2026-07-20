import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const readSource = (path: string) => readFileSync(resolve(process.cwd(), path), "utf8");

const viteSource = readSource("vite.config.ts");
const packageJson = JSON.parse(readSource("package.json")) as {
  devDependencies?: Record<string, string>;
};
const styleSource = readSource("src/style.css");
const indexSource = readSource("index.html");

describe("legacy Android WebView compatibility", () => {
  it("ships a nomodule fallback for Android 7 era WebViews", () => {
    expect(packageJson.devDependencies).toHaveProperty("@vitejs/plugin-legacy");
    expect(viteSource).toContain("@vitejs/plugin-legacy");
    expect(viteSource).toContain("legacy({");
    expect(viteSource).toContain('"Chrome >= 51"');
    expect(viteSource).toContain("Android 7 can still ship a Chrome 51 era system WebView");
    expect(viteSource).toContain('minify: "terser"');
  });

  it("keeps critical shell geometry when inset, clamp and svh are unsupported", () => {
    expect(styleSource).toContain("--topbar-height: 84px;");
    expect(styleSource).toContain("@supports (height: 1svh) and (height: clamp(1px, 2px, 3px))");
    expect(styleSource).not.toContain("@supports (height: clamp(1px, 2px, 3px)) {");
    expect(styleSource).toMatch(/\.launcher-shell\s*\{[\s\S]*height:\s*100vh;[\s\S]*height:\s*100svh;/);
    expect(styleSource).toMatch(/\.pages\s*\{[\s\S]*top:\s*var\(--topbar-height\);[\s\S]*right:\s*0;[\s\S]*bottom:\s*0;[\s\S]*left:\s*0;/);
    expect(styleSource).toMatch(/\.page\s*\{[\s\S]*top:\s*0;[\s\S]*right:\s*0;[\s\S]*bottom:\s*0;[\s\S]*left:\s*0;/);
  });

  it("shows an actionable message if neither JavaScript bundle can start", () => {
    expect(indexSource).toContain("launcher-compatibility-fallback");
    expect(indexSource).toContain("Android System WebView");
  });
});
