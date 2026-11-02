/** 正準 JSON（TS 版）が Kotlin/旧 Python の正準形・コミット済みの特徴量スキーマ hash と一致し、浮動小数・重複キーを拒否する。 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { canonicalString, featureSchemaHash, loadCanonicalFile, sha256Hex } from "../../src/lib/canonical.js";
import { FEATURE_SCHEMA_DIR, FEATURE_SCHEMA_FILES } from "../../src/release/repo-facts.js";
import { ROOT } from "./helpers.js";

describe("canonical json", () => {
  it("sorts keys by code point, no whitespace, keeps non-ascii as utf-8", () => {
    assert.equal(
      canonicalString({ b: 1, a: [true, null, "é"], "😀": 0, z: { y: 1, x: 2 } }),
      '{"a":[true,null,"é"],"b":1,"z":{"x":2,"y":1},"😀":0}',
    );
    // UTF-16 の単位ではなくコードポイント順（U+FF5E は U+1F600 より前）
    assert.equal(canonicalString({ "😀": 1, "～": 2 }), '{"～":2,"😀":1}');
    assert.equal(canonicalString('a"\\\n\u0001'), '"a\\"\\\\\\n\\u0001"');
  });
  it("refuses non-integers and unsafe integers (float formatting is Kotlin's job)", () => {
    for (const bad of [1.5, Number.NaN, Number.POSITIVE_INFINITY, 2 ** 53]) assert.throws(() => canonicalString({ x: bad }), /整数以外/);
  });
  it("matches the committed feature schema hashes (written by Kotlin)", () => {
    for (const fname of Object.values(FEATURE_SCHEMA_FILES)) {
      const raw = readFileSync(join(ROOT, FEATURE_SCHEMA_DIR, fname));
      const schema = loadCanonicalFile(raw, fname);
      assert.equal(featureSchemaHash(schema), schema["hash"], fname);
    }
    // 既知値（Kotlin の FeatureSchemaFixtureTest と同じファイル。値の変更は Kotlin 側の定義変更を意味する）
    const market = loadCanonicalFile(readFileSync(join(ROOT, FEATURE_SCHEMA_DIR, FEATURE_SCHEMA_FILES.market)), "m");
    assert.equal(market["hash"], "sha256:b86195e2bda484d3e724ea355c946ddb0855f61815165177f7686e64bfcb8959");
    assert.equal(sha256Hex({}), "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
  });
  it("strict load rejects duplicate keys, whitespace, BOM and non-objects", () => {
    assert.throws(() => loadCanonicalFile(Buffer.from('{"a":1,"a":2}'), "x"), /正準形ではない/);
    assert.throws(() => loadCanonicalFile(Buffer.from('{"a": 1}'), "x"), /正準形ではない/);
    assert.throws(() => loadCanonicalFile(Buffer.from('{"b":1,"a":2}'), "x"), /正準形ではない/);
    assert.throws(() => loadCanonicalFile(Buffer.from('{"a":1.0}'), "x"), /正準形ではない/);
    assert.throws(() => loadCanonicalFile(Buffer.from('﻿{"a":1}'), "x"), /BOM/);
    assert.throws(() => loadCanonicalFile(Buffer.from("[1]"), "x"), /オブジェクトではない/);
    assert.deepEqual(loadCanonicalFile(Buffer.from('{"a":1}'), "x"), { a: 1 });
  });
});
