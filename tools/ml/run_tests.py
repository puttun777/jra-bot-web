"""unittest ランナー。0 件・過少・スキップ過多を失敗にする。

  python3 -m tools.ml.run_tests --min-tests 20 --max-skipped 0
"""
import argparse
import sys
import unittest


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-tests", type=int, default=1)
    ap.add_argument("--max-skipped", type=int, default=None)
    args = ap.parse_args(argv)
    suite = unittest.defaultTestLoader.discover("tools/ml/tests", top_level_dir=".")
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    ran, skipped = result.testsRun, len(result.skipped)
    print(f"ran={ran} skipped={skipped} failures={len(result.failures)} errors={len(result.errors)}")
    if not result.wasSuccessful():
        return 1
    if ran < args.min_tests or ran - skipped <= 0:
        print(f"ERROR: 実行テスト数が不足（ran={ran}, skipped={skipped}, 最低={args.min_tests}）", file=sys.stderr)
        return 1
    if args.max_skipped is not None and skipped > args.max_skipped:
        print(f"ERROR: スキップ数 {skipped} が上限 {args.max_skipped} を超過", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
