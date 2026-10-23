"""コミット済みフィクスチャの静的検証（旧実装は不要）。失敗時は非 0 で終了し、フィクスチャは書き換えない。

  python -m tools.golden.verify [--dir DIR]
"""
import argparse
import math
import re
import sys
from pathlib import Path

from . import constants as C, fixtures_io, jsonutil
from .catalog import CATALOG, KOTLIN_PORT, VALID_STATUS

REQUIRED = ("fixture_id", "case_id", "schema_version", "source_repository", "source_commit", "source_function",
            "input", "expected_output", "execution_parameters", "comparison_rules", "data_classification",
            "scenario_ids", "known_defects", "generator")
REQUIRED_EXEC = ("seed", "fixed_time", "timezone", "libraries")
FORBIDDEN = [  # 合成入力に現れてはならないパターン（秘密情報・実データの混入検知）
    (re.compile(r"discord(app)?\.com/api/webhooks", re.I), "Discord Webhook"),
    (re.compile(r"https?://", re.I), "URL"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), "メールアドレス"),
    (re.compile(r"\.(sqlite3?|db|pkl|pickle)\b", re.I), "DB/モデルファイル名"),
    (re.compile(r"[A-Za-z0-9_\-]{40,}"), "長いトークン様文字列"),
    (re.compile(r"-----BEGIN"), "秘密鍵"),
    (re.compile(r"\bJV[A-Z]?[-_]?(DATA|LINK)\b", re.I), "JV-Link/JV-Data 由来の記述"),
]
SHA40 = re.compile(r"^[0-9a-f]{40}$")


def _walk_strings(o, path="$"):
    if isinstance(o, str):
        yield path, o
    elif isinstance(o, dict):
        for k, v in o.items():
            yield from _walk_strings(v, f"{path}.{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from _walk_strings(v, f"{path}[{i}]")


def _walk_numbers(o, path="$"):
    if isinstance(o, bool):
        return
    if isinstance(o, (int, float)):
        yield path, o
    elif isinstance(o, dict):
        for k, v in o.items():
            yield from _walk_numbers(v, f"{path}.{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from _walk_numbers(v, f"{path}[{i}]")


def check_rules(rules, errs, where):
    try:
        d = rules["default"]["float"]
        assert d["mode"] in ("abs_tol", "exact")
        assert isinstance(d["abs_tol"], float) and 0.0 <= d["abs_tol"] <= 1e-6
        for o in rules["overrides"]:
            assert o["mode"] in ("abs_tol", "exact", "ignore") and isinstance(o["path"], str) and o["path"]
            if o["mode"] == "abs_tol":
                assert isinstance(o["abs_tol"], float) and 0.0 <= o["abs_tol"] <= 1e-6
        assert rules["strict"] == {"types": True, "key_set": True, "list_order": True, "nonfinite": "reject"}
    except (KeyError, TypeError, AssertionError):
        errs.append(f"{where}: comparison_rules が不正（abs_tol は 0〜1e-6 の float、strict 設定は固定）")


def verify_dir(directory):
    errs = []
    d = Path(directory)
    if not (d / C.MANIFEST_NAME).exists():
        return [f"{C.MANIFEST_NAME} がありません"], {}
    try:
        manifest = fixtures_io.load_manifest(d)
        loaded = fixtures_io.load_dir(d)
    except (ValueError, OSError) as e:
        return [f"JSON として不正: {e}"], {}

    if manifest.get("schema_version") not in C.SUPPORTED_SCHEMA_VERSIONS:
        errs.append("manifest: 未対応の schema_version")
    if manifest.get("source_commit") != C.PINNED_COMMIT:
        errs.append(f"manifest: source_commit が固定値と異なる: {manifest.get('source_commit')}")
    mf = {m["path"]: m for m in manifest.get("fixtures", [])}
    if set(mf) != set(loaded):
        errs.append(f"manifest と実ファイルが不一致: manifest のみ={sorted(set(mf)-set(loaded))} 実ファイルのみ={sorted(set(loaded)-set(mf))}")
    if len(mf) != len(manifest.get("fixtures", [])):
        errs.append("manifest: パス重複")

    ids, fx_ids, by_case = set(), set(), {}
    for name, (raw, fx) in loaded.items():
        w = name
        if name in mf and fixtures_io.sha256_bytes(raw) != mf[name]["sha256"]:
            errs.append(f"{w}: manifest の sha256 と不一致（改ざん、または再生成後に manifest 未更新）")
        if not isinstance(fx, dict):
            errs.append(f"{w}: トップレベルがオブジェクトでない")
            continue
        missing = [k for k in REQUIRED if k not in fx]
        if missing:
            errs.append(f"{w}: 必須メタデータなし {missing}")
            continue
        if fx["schema_version"] not in C.SUPPORTED_SCHEMA_VERSIONS:
            errs.append(f"{w}: 未対応の schema_version={fx['schema_version']}")
        if fx["source_repository"] != C.SOURCE_REPOSITORY:
            errs.append(f"{w}: source_repository が想定外")
        if not (isinstance(fx["source_commit"], str) and SHA40.match(fx["source_commit"])):
            errs.append(f"{w}: source_commit が完全 SHA でない")
        elif fx["source_commit"] != C.PINNED_COMMIT:
            errs.append(f"{w}: source_commit が固定値と異なる")
        if name != fixtures_io.fixture_filename(fx["case_id"]):
            errs.append(f"{w}: ファイル名と case_id が不一致")
        if fx["case_id"] in ids:
            errs.append(f"{w}: case_id 重複")
        ids.add(fx["case_id"])
        if fx["fixture_id"] in fx_ids:
            errs.append(f"{w}: fixture_id 重複")
        fx_ids.add(fx["fixture_id"])
        by_case[fx["case_id"]] = fx
        if fx["data_classification"] != "synthetic":
            errs.append(f"{w}: data_classification が synthetic でない")
        if not fx["source_function"]:
            errs.append(f"{w}: source_function が空")
        ep = fx["execution_parameters"]
        if not isinstance(ep, dict) or any(k not in ep for k in REQUIRED_EXEC):
            errs.append(f"{w}: execution_parameters に {REQUIRED_EXEC} が必要")
        elif ep["timezone"] != C.TIMEZONE or ep["seed"] != C.SEED:
            errs.append(f"{w}: seed/timezone が固定値と異なる")
        # 入力・期待値の存在と形
        calls = fx["input"].get("calls") if isinstance(fx["input"], dict) else None
        results = fx["expected_output"].get("results") if isinstance(fx["expected_output"], dict) else None
        if not calls or not results:
            errs.append(f"{w}: input.calls / expected_output.results が空または不正")
        elif [c.get("call_id") for c in calls] != [r.get("call_id") for r in results]:
            errs.append(f"{w}: input.calls と expected_output.results の call_id が対応しない")
        else:
            if len({c["call_id"] for c in calls}) != len(calls):
                errs.append(f"{w}: call_id 重複")
            for r in results:
                oc = r.get("outcome", {})
                if oc.get("status") == "ok" and "value" in oc:
                    pass
                elif oc.get("status") == "exception" and "exception_type" in oc:
                    pass
                else:
                    errs.append(f"{w}: {r.get('call_id')} の outcome が不正")
        # 数値の型・有限性
        for path, v in _walk_numbers(fx["expected_output"]):
            if isinstance(v, float) and not math.isfinite(v):
                errs.append(f"{w}: {path} が非有限")
        for path in jsonutil.find_nonfinite(fx["expected_output"]) + jsonutil.find_nonfinite(fx["input"]):
            errs.append(f"{w}: {path} に NaN/Infinity（タグ付き）。許可されていない")
        check_rules(fx["comparison_rules"], errs, w)
        # 再現可能な形式（正準形式で書かれていること）
        if raw.decode("utf-8") != jsonutil.dumps(fx):
            errs.append(f"{w}: 正準 JSON 形式（キー昇順・indent=2・末尾改行）でない")
        # 合成データのみ
        for path, s in _walk_strings(fx["input"]):
            for rx, label in FORBIDDEN:
                if rx.search(s):
                    errs.append(f"{w}: input の {path} に禁止パターン（{label}）")

    # カタログ整合
    seen = set()
    for e in CATALOG:
        if e["id"] in seen:
            errs.append(f"catalog: id 重複 {e['id']}")
        seen.add(e["id"])
        if e["status"] not in VALID_STATUS:
            errs.append(f"catalog: {e['id']} の status が不正")
        if e["status"] == "planned" and e["case_ids"]:
            errs.append(f"catalog: planned の {e['id']} に case_ids がある")
        if e["status"] != "planned" and not e["case_ids"]:
            errs.append(f"catalog: {e['id']} は {e['status']} だが case_ids が空")
        if not e.get("remaining"):
            errs.append(f"catalog: {e['id']} に残作業/未実装理由がない")
        for cid in e["case_ids"]:
            if cid not in by_case:
                errs.append(f"catalog: {e['id']} が存在しないフィクスチャ {cid} を参照")
    # Kotlin 移植の対応表: 全ケースがちょうど 1 回、対応/未対応に分類されていること（未対応には理由）
    sup, uns = KOTLIN_PORT["supported"], KOTLIN_PORT["unsupported"]
    if len(set(sup)) != len(sup):
        errs.append("catalog: KOTLIN_PORT.supported に重複がある")
    for cid in sorted(set(sup) & set(uns)):
        errs.append(f"catalog: KOTLIN_PORT の対応と未対応の両方に {cid}")
    for cid in sorted((set(sup) | set(uns)) - set(by_case)):
        errs.append(f"catalog: KOTLIN_PORT が存在しないフィクスチャ {cid} を参照")
    for cid in sorted(set(by_case) - set(sup) - set(uns)):
        errs.append(f"catalog: KOTLIN_PORT に分類されていないフィクスチャ {cid}")
    for cid, reason in uns.items():
        if not reason:
            errs.append(f"catalog: KOTLIN_PORT の未対応 {cid} に理由がない")
    for cid, fx in by_case.items():
        for sid in fx["scenario_ids"]:
            if sid not in seen:
                errs.append(f"{cid}: scenario_ids に未登録の {sid}")
    return errs, by_case


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", default=str(C.FIXTURE_DIR))
    args = ap.parse_args(argv)
    errs, by_case = verify_dir(args.dir)
    if errs:
        print("フィクスチャ検証に失敗しました:", file=sys.stderr)
        for e in errs:
            print("  - " + e, file=sys.stderr)
        return 1
    n_calls = sum(len(fx["input"]["calls"]) for fx in by_case.values())
    st = {s: sum(1 for e in CATALOG if e["status"] == s) for s in VALID_STATUS}
    print(f"OK: {len(by_case)} ケース / {n_calls} 呼び出し。カタログ {len(CATALOG)} 項目: "
          f"implemented={st['implemented']} partial={st['partial']} planned={st['planned']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
