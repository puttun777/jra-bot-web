"""旧実装（固定コミット）から合成入力のフィクスチャを生成する。

  python -m tools.golden.generate --legacy-path /path/to/jra-bot --check    # 書き込まず、コミット済みとの一致を検証（浮動小数は各ケースの許容誤差内）
  python -m tools.golden.generate --legacy-path /path/to/jra-bot --update   # 明示的に fixtures/ を更新（差分は PR で確認）

固定コミット以外・未コミット変更のある参照元は常に拒否する（上書き許可オプションは無い）。
"""
import argparse
import sys
from pathlib import Path

from . import adapter, compare, constants as C, fixtures_io, jsonutil, legacy
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


def _regeneration_rules(committed_rules):
    """コミット済みフィクスチャ自身の比較ルールを再生成の判定に使う（新しい許容値は作らない）。

    完全一致のケース（ハッシュ・既定値）は完全一致のまま。例外メッセージの ignore だけは、
    Kotlin 側の文言差を許すための設定なので、旧実装の出力を見る再生成では外して厳密に比べる。
    """
    rules = dict(committed_rules)
    rules["overrides"] = [o for o in committed_rules.get("overrides", []) if o.get("mode") != "ignore"]
    return rules


def _fixture_problems(committed: bytes, regenerated: bytes):
    """expected_output だけ、そのフィクスチャの許容誤差内の差を許す。他は完全一致。"""
    c = jsonutil.loads(committed.decode("utf-8"))
    r = jsonutil.loads(regenerated.decode("utf-8"))
    c_out, r_out = c.pop("expected_output"), r.pop("expected_output")
    if c != r:
        return ["expected_output 以外（入力・出典・ルール・メタデータ）が異なります"]
    return [f"{d['path']}: {d['kind']} expected={d['expected']!r} actual={d['actual']!r}"
            for d in compare.diff(c_out, r_out, _regeneration_rules(c["comparison_rules"]))]


def regeneration_problems(committed: dict, regenerated: dict):
    """{filename: bytes} 同士を比べ、問題の一覧を返す（空なら一致）。

    バイト完全一致は要求しない: 浮動小数の最後の 1〜2 桁は CPU・libm の実装で変わり得る
    （GitHub のランナーの世代で観測。G1-MODEL-001 の 0.05767062618090029 と …027）。
    変わってよいのは各フィクスチャの許容誤差内の expected_output の浮動小数だけ。
    manifest の sha256 は、コミット済みファイルの内容に対する値なので、ここでは比べない（verify が検査する）。
    """
    problems = []
    for n in sorted(set(regenerated) | set(committed)):
        if n not in regenerated:
            problems.append(f"余分なファイル: {n}")
        elif n not in committed:
            problems.append(f"欠落: {n}")
        elif committed[n] == regenerated[n]:
            continue
        elif n == C.MANIFEST_NAME:
            strip = lambda b: {k: ([{kk: vv for kk, vv in e.items() if kk != "sha256"} for e in v]  # noqa: E731
                                   if k == "fixtures" else v)
                               for k, v in jsonutil.loads(b.decode("utf-8")).items()}
            if strip(committed[n]) != strip(regenerated[n]):
                problems.append(f"差分: {n}（sha256 以外）")
        else:
            problems += [f"差分: {n}: {m}" for m in _fixture_problems(committed[n], regenerated[n])]
    return problems


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
        committed = {p.name: p.read_bytes() for p in out.glob("*.json")}
        bad = regeneration_problems(committed, files)
        if bad:
            print("再生成結果がコミット済みフィクスチャと一致しません:\n  " + "\n  ".join(bad), file=sys.stderr)
            return 1
        print(f"OK: {len(files) - 1} フィクスチャ + manifest が再生成結果と一致（浮動小数は各ケースの許容誤差内。commit {C.PINNED_COMMIT[:7]}）")
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
