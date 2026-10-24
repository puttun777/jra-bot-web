"""学習用データセット（`export-dataset` の出力）の契約検証。Python の学習側（Stage 3-F2 の `ml-train`）が**入力を受け取る前に必ず実行する**。

  python3 -m tools.ml.dataset_contract --csv-gz X.csv.gz --manifest X.manifest.json [--feature-schema feature_schema.json]

特徴量の名前・順序・ハッシュは Kotlin（`FeatureSchema`）が唯一の定義で、ここでは**再実装せず**、manifest（と任意の `feature_schema.json`）から読んで検証する。
検証項目（docs/ML_DESIGN.md §11）: manifest の自己ハッシュ、feature_schema の hash、列の構成と順序、CSV の SHA-256 と行数、数値の有限性、ラベルが 0/1、
split の整合（valid は常に train より後の日・同じ日を分けない・件数）、as_of が発走前でレース日と矛盾しない、`legacy_compat_used=false`。
失敗は `ContractError`（終了コード 1）。学習側は、検証に失敗したデータセットで学習しない。
"""
import argparse
import csv
import datetime as dt
import gzip
import hashlib
import io
import json
import math
import pathlib
import sys

from . import canonical

META_COLUMNS = ["tenant_id", "race_id", "umaban", "race_date", "as_of"]
TAIL_COLUMNS = ["label", "split"]


class ContractError(Exception):
    pass


def _require(cond, message):
    if not cond:
        raise ContractError(message)


def load_manifest(raw: bytes) -> dict:
    manifest = canonical.loads_strict(raw)
    _require(isinstance(manifest, dict), "manifest がオブジェクトでない")
    return manifest


def verify(csv_gz: bytes, manifest_raw: bytes, feature_schema_raw: bytes | None = None) -> dict:
    """検証して要約（行数など）を返す。"""
    manifest = load_manifest(manifest_raw)
    _require(manifest.get("dataset_schema_version") == 1, "未対応の dataset_schema_version")
    _require(manifest_raw.rstrip(b"\n") == canonical.dumps(manifest), "manifest が正準 JSON でない")
    _require(canonical.sha256_hex({k: v for k, v in manifest.items() if k != "sha256"}) == manifest["sha256"], "manifest の sha256 が内容と一致しない")

    schema = manifest["feature_schema"]
    _require(canonical.feature_schema_hash(schema) == schema["hash"], "feature_schema の hash が内容と一致しない")
    if feature_schema_raw is not None:
        _require(canonical.loads_strict(feature_schema_raw) == schema, "feature_schema.json が manifest の feature_schema と違う")
    names = schema["names"]
    columns = META_COLUMNS + names + TAIL_COLUMNS
    _require(manifest["columns"] == columns, "manifest.columns が『meta + feature_schema の順 + label + split』でない")

    builder = manifest["builder"]
    _require(builder["mode"] == "point_in_time" and builder["legacy_compat_used"] is False, "旧互換モードで作られたデータセットは学習に使わない（H3/H8）")

    text_bytes = gzip.decompress(csv_gz)
    _require(hashlib.sha256(text_bytes).hexdigest() == manifest["csv"]["sha256"], "CSV の sha256 が manifest と一致しない")
    _require(len(text_bytes) == manifest["csv"]["bytes"], "CSV のバイト数が manifest と一致しない")
    _require(b"\r" not in text_bytes and text_bytes.endswith(b"\n"), "改行は LF で、末尾に改行が必要")

    rows = list(csv.reader(io.StringIO(text_bytes.decode("utf-8"))))
    _require(rows and rows[0] == columns, "CSV のヘッダが manifest.columns と違う")
    body = rows[1:]
    _require(len(body) == manifest["rows"], "行数が manifest と一致しない")

    n_feat = len(names)
    f0 = len(META_COLUMNS)
    prev_key = None
    train_dates, valid_dates = set(), set()
    positives = 0
    sums = [None] * n_feat
    constant = [True] * n_feat
    lead = manifest["as_of_policy"]["lead_seconds"]
    for i, r in enumerate(body, start=2):
        _require(len(r) == len(columns), f"{i} 行目: 列数が違う")
        tenant, race_id, umaban, race_date, as_of = r[0], r[1], r[2], r[3], r[4]
        _require(int(tenant) == manifest["tenant_id"], f"{i} 行目: tenant_id が manifest と違う（テナント越境）")
        _require(len(race_id) == 12 and race_id.isdigit(), f"{i} 行目: race_id の形式")
        _require(umaban.isdigit() and int(umaban) >= 1, f"{i} 行目: umaban")
        d = dt.date.fromisoformat(race_date)
        _require(race_id[:8] == d.strftime("%Y%m%d"), f"{i} 行目: race_id の先頭 8 桁とレース日が違う")
        ts = dt.datetime.fromisoformat(as_of.replace("Z", "+00:00"))
        _require(ts.tzinfo is not None, f"{i} 行目: as_of にタイムゾーンが無い")
        jst_date = (ts + dt.timedelta(hours=9)).date()
        _require(jst_date == d, f"{i} 行目: as_of（JST の日付）がレース日と違う（未来の情報を見ている可能性）")
        key = (race_date, race_id, int(umaban))
        _require(prev_key is None or key > prev_key, f"{i} 行目: 行が (レース日, race_id, 馬番) の昇順でない/重複")
        prev_key = key
        for j in range(n_feat):
            x = float(r[f0 + j])
            _require(math.isfinite(x), f"{i} 行目: {names[j]} が有限でない")
            if sums[j] is None:
                sums[j] = x
            elif x != sums[j]:
                constant[j] = False
        label, split = r[-2], r[-1]
        _require(label in ("0", "1"), f"{i} 行目: label が 0/1 でない")
        positives += int(label)
        _require(split in ("train", "valid"), f"{i} 行目: split が train/valid でない")
        (train_dates if split == "train" else valid_dates).add(race_date)

    _require(not (train_dates & valid_dates), "同じ日が train と valid に分かれている")
    _require(not train_dates or not valid_dates or max(train_dates) < min(valid_dates), "valid が train より前の日を含む（時系列の分割になっていない）")
    split = manifest["split"]
    n_valid = sum(1 for r in body if r[-1] == "valid")
    _require(split["n_train"] == len(body) - n_valid and split["n_valid"] == n_valid, "split の件数が manifest と違う")
    _require(split["valid_starts_at"] == (min(valid_dates) if valid_dates else None), "valid_starts_at が違う")
    _require(manifest["label"]["positives"] == positives, "positives が manifest と違う")
    _require(manifest["quality"]["constant_features"] == [names[j] for j in range(n_feat) if constant[j] and body], "constant_features が実データと違う")
    _require(lead > 0, "as_of のリードが正でない")
    return {"rows": len(body), "n_train": len(body) - n_valid, "n_valid": n_valid, "positives": positives, "features": n_feat}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv-gz", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--feature-schema")
    args = ap.parse_args(argv)
    try:
        fs = pathlib.Path(args.feature_schema).read_bytes() if args.feature_schema else None
        summary = verify(pathlib.Path(args.csv_gz).read_bytes(), pathlib.Path(args.manifest).read_bytes(), fs)
    except (ContractError, ValueError, KeyError, OSError) as e:
        print(f"データセット契約の検証に失敗: {e}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
