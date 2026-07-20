import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const serverSource = readFileSync(
  resolve(process.cwd(), "Server/LauncherDiagnosticsServer.ps1"),
  "utf8",
);

describe("launcher diagnostics server", () => {
  it("accepts explicit raw launcher-log uploads with a 10 MiB ceiling", () => {
    expect(serverSource).toContain('$logUploadPath = "/api/launcher-diagnostics/upload-log"');
    expect(serverSource).toContain("$maxLogBytes = 10MB");
    expect(serverSource).toContain('$request.Headers["X-Product-Key"]');
    expect(serverSource).toContain('$request.Headers["X-Installation-Id"]');
    expect(serverSource).toContain('$request.Headers["X-Launcher-Version"]');
    expect(serverSource).toContain("Read-RequestBytes");
    expect(serverSource).toContain("$logUploadRateLimit");
    expect(serverSource).toContain("Log upload rate limit exceeded");
  });

  it("stores uploads separately with sanitized metadata", () => {
    expect(serverSource).toContain('$uploadRoot = Join-Path $storageRoot "uploads"');
    expect(serverSource).toContain("Get-SafePathPart");
    expect(serverSource).toContain("launcher-log-");
    expect(serverSource).toContain("Set-Content -LiteralPath $metadataPath");
    expect(serverSource).toContain("WriteAllBytes($logPath, $content)");
  });
});
