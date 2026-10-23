"""旧実装（固定コミット）から合成入力のフィクスチャを生成する。

  python -m tools.golden.generate --legacy-path /path/to/jra-bot --check    # 書き込まず、コミット済みとの一致を検証
  python -m tools.golden.generate --legacy-path /path/to/jra-bot --update   # 明示的に fixtures/ を更新（差分は PR で確認）

固定コミット以外・未コミット変更のある参照元は常に拒否する（上書き許可オプションは無い）。
"""
import argparse
import sys
from pathlib import Path

from . import adapter, constants as C, fixtures_io, legacy
from .scenarios import CASES


def generate_files(legacy_path):
    """全ケースを 2 回実行して結果が一致することを確認し、{filename: bytes} を返す。"""
    L = legacy.load(legacy_path)
    fixtures = {}
    for case in CASES:
        with legacy.sandbox():
            out1 = adapter.run_calls(L, case.calls)
        with legacy.sandbox():
            out2 = adapter.run_calls(L, case.calls)
        if out1 != out2:
            raise legacy.LegacyError(f"{case.case_id}: 同一入力で 2 回の結果が異なります（非決定的）")
        fixtures[case.case_id] = fixtures_io.build_fixture(case, out1)
    return fixtures_io.render_all(fixtures)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--legacy-path", required=True, help="jra-bot のチェックアウト（固定コミット）")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="書き込まず、再生成結果がコミット済みと一致するか検証")
    mode.add_argument("--update", action="store_true", help="fixtures/ を上書き更新（明示操作）")
    ap.add_argument("--out", default=str(C.FIXTURE_DIR), help="出力先/比較先ディレクトリ")
    args = ap.parse_args(argv)

    try:
        files = generate_files(args.legacy_path)
    except legacy.LegacyError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    out = Path(args.out)
    if args.check:
        bad = []
        names = set(files) | {p.name for p in out.glob("*.json")}
        for n in sorted(names):
            p = out / n
            if n not in files:
                bad.append(f"余分なファイル: {n}")
            elif not p.exists():
                bad.append(f"欠落: {n}")
            elif p.read_bytes() != files[n]:
                bad.append(f"差分: {n}")
        if bad:
            print("再生成結果がコミット済みフィクスチャと一致しません:\n  " + "\n  ".join(bad), file=sys.stderr)
            return 1
        print(f"OK: {len(files) - 1} フィクスチャ + manifest が再生成結果と完全一致（commit {C.PINNED_COMMIT[:7]}）")
        return 0
    out.mkdir(parents=True, exist_ok=True)
    for p in out.glob("*.json"):
        if p.name not in files:
            p.unlink()
    for n, data in files.items():
        (out / n).write_bytes(data)
    print(f"更新: {len(files) - 1} フィクスチャ + manifest → {out}（git diff で期待値の変更を確認すること）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
