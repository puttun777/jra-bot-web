"""tools/ci と tools/release の unittest を実行する。0 件・スキップ・失敗・エラーを失敗にする（黙って緑にしない）。

使い方: python -m tools.ci.run_tests --min-tests N [DIR ...]   （DIR 省略時は tools/ci/tests と tools/release/tests）
"""
import argparse
import sys
import unittest
from pathlib import Path


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--min-tests", type=int, required=True)
    p.add_argument("dirs", nargs="*", default=["tools/ci/tests", "tools/release/tests"])
    a = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    suite = unittest.TestSuite()
    for d in a.dirs:
        suite.addTests(unittest.defaultTestLoader.discover(str(root / d), top_level_dir=str(root)))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    print(f"tests={result.testsRun} skipped={len(result.skipped)} failures={len(result.failures)} errors={len(result.errors)}")
    if result.testsRun < a.min_tests or result.skipped or not result.wasSuccessful():
        print(f"::error::テストが不足（最小 {a.min_tests}）・スキップ・失敗のいずれか")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
