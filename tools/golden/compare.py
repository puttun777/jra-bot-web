"""期待値（旧 Python 由来）と実行結果（将来の Kotlin 等）の比較。

実行結果ファイル形式:  {"schema_version": 1, "results": {"<case_id>": <expected_output と同形>}}
  python -m tools.golden.compare --actual kotlin_out.json [--fixtures DIR] [--case ID ...] [--report report.json]
終了コード: 0=全一致 / 1=差分あり / 2=入力不正・未提供ケースあり（未実装を成功扱いにしない）
"""
import argparse
import math
import sys
from pathlib import Path

from . import constants as C, fixtures_io, jsonutil


def _match(pattern, segs):
    return len(pattern) <= len(segs) and all(p == "*" or p == s for p, s in zip(pattern, segs))


def resolve_rule(rules, segs):
    """最も長く一致する override を採用。無ければ default（float: abs_tol）。"""
    best = None
    for o in rules.get("overrides", []):
        pat = o["path"].split(".")
        if _match(pat, segs) and (best is None or len(pat) > len(best[0])):
            best = (pat, o)
    if best:
        return best[1]
    return dict(rules["default"]["float"], path="(default)")


def _fmt(segs):
    out = ""
    for s in segs:
        out += f"[{s}]" if isinstance(s, int) else (("." if out else "") + str(s))
    return out or "$"


def _type(v):
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, int):
        return "int"
    if isinstance(v, float):
        return "float"
    if isinstance(v, str):
        return "str"
    if v is None:
        return "null"
    if isinstance(v, list):
        return "list"
    if isinstance(v, dict):
        return "object"
    return type(v).__name__


def diff(expected, actual, rules, segs=(), out=None):
    out = [] if out is None else out
    key_segs = tuple("*" if isinstance(s, int) else s for s in segs)
    rule = resolve_rule(rules, key_segs)
    path = _fmt(segs)

    def add(kind, exp, act, **extra):
        out.append(dict(path=path, kind=kind, expected=exp, actual=act, rule=rule, **extra))

    if rule.get("mode") == "ignore":
        return out
    te, ta = _type(expected), _type(actual)
    if te != ta:
        add("type_mismatch", expected, actual, expected_type=te, actual_type=ta)
    elif te == "object":
        if set(expected) == {jsonutil.NONFINITE_KEY} or set(actual) == {jsonutil.NONFINITE_KEY}:
            # 非有限値はタグ付き同士の完全一致のみ許容（暗黙の許容はしない）
            if expected != actual:
                add("nonfinite_mismatch", expected, actual)
            return out
        for k in sorted(set(expected) - set(actual)):
            out.append(dict(path=_fmt(segs + (k,)), kind="missing_key", expected=expected[k], actual=None, rule=rule))
        for k in sorted(set(actual) - set(expected)):
            out.append(dict(path=_fmt(segs + (k,)), kind="unexpected_key", expected=None, actual=actual[k], rule=rule))
        for k in sorted(set(expected) & set(actual)):
            diff(expected[k], actual[k], rules, segs + (k,), out)
    elif te == "list":
        if len(expected) != len(actual):
            add("length_mismatch", len(expected), len(actual))
        for i, (e, a) in enumerate(zip(expected, actual)):
            diff(e, a, rules, segs + (i,), out)
    elif te == "float":
        if not (math.isfinite(actual) and math.isfinite(expected)):
            add("nonfinite_mismatch", expected, actual)
        elif rule.get("mode") == "exact" or rule.get("abs_tol", 0.0) == 0.0:
            if expected != actual:
                add("value_mismatch", expected, actual, abs_diff=abs(expected - actual))
        elif abs(expected - actual) > rule["abs_tol"]:
            add("value_mismatch", expected, actual, abs_diff=abs(expected - actual), abs_tol=rule["abs_tol"])
    elif expected != actual:
        add("value_mismatch", expected, actual)
    return out


def compare_case(fixture, actual_output):
    return diff(fixture["expected_output"], actual_output, fixture["comparison_rules"])


def compare_all(fixtures, actual_doc, only=None):
    """fixtures: {case_id: fixture}。actual_doc: {"schema_version", "results"}。レポート dict を返す。"""
    report = {"schema_version": 1, "summary": {}, "cases": []}
    results = actual_doc.get("results")
    if actual_doc.get("schema_version") != 1 or not isinstance(results, dict):
        raise ValueError("実行結果ファイルの形式が不正です（schema_version=1 と results オブジェクトが必要）")
    ids = sorted(only) if only else sorted(fixtures)
    unknown = [i for i in ids if i not in fixtures]
    if unknown:
        raise ValueError(f"フィクスチャに無いケース: {unknown}")
    counts = dict(passed=0, failed=0, not_provided=0)
    for cid in ids:
        if cid not in results:
            counts["not_provided"] += 1
            report["cases"].append({"case_id": cid, "status": "not_provided", "diffs": []})
            continue
        ds = compare_case(fixtures[cid], results[cid])
        status = "passed" if not ds else "failed"
        counts[status] += 1
        report["cases"].append({"case_id": cid, "status": status, "diffs": ds})
    report["summary"] = counts
    return report


def format_report(report, limit=20):
    lines = []
    for c in report["cases"]:
        lines.append(f"[{c['status'].upper()}] {c['case_id']}")
        for d in c["diffs"][:limit]:
            tol = d["rule"].get("abs_tol")
            lines.append(f"    {d['path']}: {d['kind']} expected={d['expected']!r} actual={d['actual']!r}"
                         + (f" abs_diff={d['abs_diff']:.3e}" if "abs_diff" in d else "")
                         + (f" tol={tol}" if tol is not None else ""))
        if len(c["diffs"]) > limit:
            lines.append(f"    ... 他 {len(c['diffs']) - limit} 件（--report の JSON に全件）")
    s = report["summary"]
    lines.append(f"合計: passed={s['passed']} failed={s['failed']} not_provided={s['not_provided']}")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--actual", required=True)
    ap.add_argument("--fixtures", default=str(C.FIXTURE_DIR))
    ap.add_argument("--case", action="append", help="対象ケースを限定（複数指定可）")
    ap.add_argument("--report", help="機械処理用 JSON レポートの出力先")
    args = ap.parse_args(argv)
    try:
        fixtures = {fx["case_id"]: fx for _, (_, fx) in fixtures_io.load_dir(Path(args.fixtures)).items()}
        actual = jsonutil.loads(Path(args.actual).read_text(encoding="utf-8"))
        report = compare_all(fixtures, actual, args.case)
    except (OSError, ValueError, KeyError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    print(format_report(report))
    if args.report:
        Path(args.report).write_text(jsonutil.dumps(report), encoding="utf-8")
    s = report["summary"]
    return 2 if s["not_provided"] else (1 if s["failed"] else 0)


if __name__ == "__main__":
    sys.exit(main())
