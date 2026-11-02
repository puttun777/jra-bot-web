import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { repoRoot } from "../../src/release/repo-facts.js";

export const ROOT = repoRoot();

export function withTempDir<T>(fn: (dir: string) => T): T {
  const d = mkdtempSync(join(tmpdir(), "jratools-"));
  try {
    return fn(d);
  } finally {
    rmSync(d, { recursive: true, force: true });
  }
}
