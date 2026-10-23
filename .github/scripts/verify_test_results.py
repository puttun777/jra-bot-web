#!/usr/bin/env python3
"""JUnit の XML レポートを検証する。0 件・スキップ・失敗・エラー・期待したテストクラスの欠落を失敗にする（黙って緑にしない）。

使い方: verify_test_results.py <ラベル> <レポートのディレクトリ> <最小テスト数> <期待するクラス名(カンマ区切り。単純名)>
"""
import glob
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    label, directory, minimum, classes = sys.argv[1], sys.argv[2], int(sys.argv[3]), [c for c in sys.argv[4].split(",") if c]
    files = glob.glob(directory + "/*.xml")
    if not files:
        print(f"::error::{label}: テスト結果が無い ({directory})")
        return 1
    seen, tests, skipped, failures, errors = set(), 0, 0, 0, 0
    for f in files:
        root = ET.parse(f).getroot()
        seen.add(root.get("name").rsplit(".", 1)[-1])
        tests += int(root.get("tests"))
        skipped += int(root.get("skipped"))
        failures += int(root.get("failures"))
        errors += int(root.get("errors"))
    print(f"{label}: tests={tests} skipped={skipped} failures={failures} errors={errors} classes={len(seen)}")
    missing = sorted(set(classes) - seen)
    if missing:
        print(f"::error::{label}: 期待したテストクラスが結果に無い: {missing}")
        return 1
    if tests < minimum or skipped or failures or errors:
        print(f"::error::{label}: 件数不足(最小 {minimum})・スキップ・失敗のいずれか")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
