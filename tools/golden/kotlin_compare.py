"""Kotlin ランナー（:golden-runner）の出力と、旧 Python のフィクスチャを比較し、対応/未対応を分けて報告する（T3.1）。

  ./gradlew :golden-runner:run --args="--fixtures tools/golden/fixtures --out build/golden/kotlin_out.json --coverage build/golden/kotlin_coverage.json"
  python3 -m tools.golden.kotlin_compare --actual build/golden/kotlin_out.json --coverage build/golden/kotlin_coverage.json [--report r.json]

- 比較するのは Kotlin が『対応』と申告したケースだけ（各フィクスチャ自身の comparison_rules を使う。`compare.py` と同じ判定）。
  未対応のケースは**成功に数えず**、ケース数・呼び出し数を別に報告する。『全件一致』とは言わない。
- 申告（coverage）と catalog.KOTLIN_PORT の対応表が食い違ったら失敗する（対応ケースが黙って減る／増えるのを防ぐ。変更は意図して表を更新する）。
- 出力に余分なケースがある・申告と出力が違う・フィクスチャの全ケースが対応/未対応に分類されていない場合は形式不正。
- 終了コード: 0=対応ケースがすべて一致 / 1=差分あり / 2=形式不正・申告と対応表の不一致。
標準ライブラリだけで動く（numpy・旧実装は不要）。
"""
import argparse
import json
import sys
from pathlib import Path

from . import compare, constants as C, fixtures_io, jsonutil
from .catalog import KOTLIN_PORT


def _is_num(v):
    return isinstance(v, float) and not isinstance(v, bool)


def float_errors(expected, actual, path=""):
    """expected と actual を並べて歩き、浮動小数どうしの絶対誤差を (path, |e-a|) で返す（型が違う箇所は compare 側が報告する）。"""
    out = []
    if _is_num(expected) and _is_num(actual):
        if expected == expected and actual == actual and abs(expected) != float("inf") and abs(actual) != float("inf"):
            out.append((path, abs(expected - actual)))
    elif isinstance(expected, dict) and isinstance(actual, dict):
        for k in sorted(set(expected) & set(actual)):
            out += float_errors(expected[k], actual[k], f"{path}.{k}" if path else k)
    elif isinstance(expected, list) and isinstance(actual, list):
        for i, (e, a) in enumerate(zip(expected, actual)):
            out += float_errors(e, a, f"{path}[{i}]")
    return out


def load_coverage(doc):
    if not isinstance(doc, dict) or doc.get("schema_version") != 1:
        raise ValueError("coverage の形式が不正です（schema_version=1 が必要）")
    sup, uns = doc.get("supported"), doc.get("unsupported")
    if not isinstance(sup, list) or not isinstance(uns, list):
        raise ValueError("coverage に supported / unsupported の配列が必要です")
    for e in sup + uns:
        if not isinstance(e, dict) or not isinstance(e.get("case_id"), str) or not isinstance(e.get("calls"), int):
            raise ValueError(f"coverage の要素が不正です: {e!r}")
    for e in uns:
        if not e.get("reason"):
            raise ValueError(f"未対応のケースに理由がありません: {e['case_id']}")
    return {e["case_id"]: e for e in sup}, {e["case_id"]: e for e in uns}


def build_report(fixtures, actual_doc, coverage_doc, port=KOTLIN_PORT):
    """問題があれば ValueError（形式不正・申告と対応表の不一致）。戻り値は報告 dict（cases に status と最大誤差）。"""
    sup, uns = load_coverage(coverage_doc)
    results = actual_doc.get("results") if isinstance(actual_doc, dict) else None
    if not isinstance(results, dict) or actual_doc.get("schema_version") != 1:
        raise ValueError("出力の形式が不正です（schema_version=1 と results オブジェクトが必要）")
    both = sorted(set(sup) & set(uns))
    if both:
        raise ValueError(f"対応と未対応の両方に載っているケース: {both}")
    classified = set(sup) | set(uns)
    if classified != set(fixtures):
        raise ValueError(f"フィクスチャとの分類が一致しない: 未分類={sorted(set(fixtures) - classified)} 不明={sorted(classified - set(fixtures))}")
    if set(results) != set(sup):
        raise ValueError(f"出力のケースが『対応』の申告と違う: 出力のみ={sorted(set(results) - set(sup))} 申告のみ={sorted(set(sup) - set(results))}")
    for cid, e in {**sup, **uns}.items():
        n = len(fixtures[cid]["input"]["calls"])
        if e["calls"] != n:
            raise ValueError(f"{cid}: 申告の呼び出し数 {e['calls']} がフィクスチャの {n} と違う")
    if set(sup) != set(port["supported"]) or set(uns) != set(port["unsupported"]):
        raise ValueError(
            "Kotlin の申告が catalog.KOTLIN_PORT と違う: "
            f"対応の差={sorted(set(sup) ^ set(port['supported']))} 未対応の差={sorted(set(uns) ^ set(port['unsupported']))}")

    rep = compare.compare_all(fixtures, actual_doc, only=list(sup))
    cases = []
    for c in rep["cases"]:
        errs = float_errors(fixtures[c["case_id"]]["expected_output"], results[c["case_id"]])
        worst = max(errs, key=lambda t: t[1]) if errs else ("", 0.0)
        cases.append({"case_id": c["case_id"], "status": c["status"], "calls": sup[c["case_id"]]["calls"],
                      "diffs": c["diffs"], "floats_compared": len(errs), "max_abs_error": worst[1], "max_abs_error_at": worst[0],
                      "tolerance": fixtures[c["case_id"]]["comparison_rules"]["default"]["float"]["abs_tol"]})
    worst_all = max(cases, key=lambda c: c["max_abs_error"]) if cases else None
    return {
        "schema_version": 1,
        "supported": {"cases": len(sup), "calls": sum(e["calls"] for e in sup.values()),
                      "passed": sum(c["status"] == "passed" for c in cases), "failed": sum(c["status"] == "failed" for c in cases),
                      "passed_calls": sum(c["calls"] for c in cases if c["status"] == "passed")},
        "unsupported": {"cases": len(uns), "calls": sum(e["calls"] for e in uns.values()),
                        "items": [{"case_id": k, "calls": v["calls"], "reason": v["reason"]} for k, v in sorted(uns.items())]},
        "total": {"cases": len(fixtures), "calls": sum(len(f["input"]["calls"]) for f in fixtures.values())},
        "max_abs_error": worst_all["max_abs_error"] if worst_all else 0.0,
        "max_abs_error_case": worst_all["case_id"] if worst_all else None,
        "max_abs_error_at": worst_all["max_abs_error_at"] if worst_all else None,
        "cases": cases,
    }


def format_report(rep, limit=20):
    s, u, t = rep["supported"], rep["unsupported"], rep["total"]
    lines = ["Kotlin と旧 Python（固定コミット）のゴールデン比較"]
    for c in rep["cases"]:
        lines.append(f"  [{c['status'].upper():6}] {c['case_id']}  calls={c['calls']}  float={c['floats_compared']}  "
                     f"最大絶対誤差={c['max_abs_error']:.3e}（許容 {c['tolerance']:g}）")
        for d in c["diffs"][:limit]:
            lines.append(f"      {d['path']}: {d['kind']} expected={d['expected']!r} actual={d['actual']!r}"
                         + (f" abs_diff={d['abs_diff']:.3e}" if "abs_diff" in d else ""))
        if len(c["diffs"]) > limit:
            lines.append(f"      ... 他 {len(c['diffs']) - limit} 件")
    lines.append("未対応（比較していない。成功に数えない）:")
    for i in u["items"]:
        lines.append(f"  [NOT PORTED] {i['case_id']}  calls={i['calls']}  理由: {i['reason']}")
    lines.append(f"対応: {s['cases']} ケース / {s['calls']} 呼び出し（一致 {s['passed']} ケース / {s['passed_calls']} 呼び出し、不一致 {s['failed']} ケース）")
    lines.append(f"未対応: {u['cases']} ケース / {u['calls']} 呼び出し")
    lines.append(f"フィクスチャ全体: {t['cases']} ケース / {t['calls']} 呼び出し（検証済み {s['passed_calls']} = {s['passed_calls'] / t['calls']:.1%}）")
    lines.append(f"最大絶対誤差: {rep['max_abs_error']:.3e}（{rep['max_abs_error_case']} {rep['max_abs_error_at']}）")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--actual", required=True)
    ap.add_argument("--coverage", required=True)
    ap.add_argument("--fixtures", default=str(C.FIXTURE_DIR))
    ap.add_argument("--report", help="機械処理用 JSON レポートの出力先")
    args = ap.parse_args(argv)
    try:
        fixtures = {fx["case_id"]: fx for _, (_, fx) in fixtures_io.load_dir(Path(args.fixtures)).items()}
        actual = jsonutil.loads(Path(args.actual).read_text(encoding="utf-8"))
        coverage = json.loads(Path(args.coverage).read_text(encoding="utf-8"))
        rep = build_report(fixtures, actual, coverage)
    except (OSError, ValueError, KeyError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    text = format_report(rep)
    print(text)
    if args.report:
        Path(args.report).write_text(jsonutil.dumps(rep), encoding="utf-8")
    return 1 if rep["supported"]["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
