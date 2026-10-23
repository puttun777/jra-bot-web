"""release-manifest の生成・検証 CLI。

  python -m tools.release.cli preview  --git-sha <40桁> --built-at 2026-11-01T00:00:00Z --local-images images-local.json --out manifest-preview.json
  python -m tools.release.cli validate --kind preview|production FILE [--expect-git-sha <40桁>]
  python -m tools.release.cli production --git-sha … --release 2026.11.0 --built-at … --registry-images registry.json \
        --min-app-release 2026.11.0 --feature-schema-hash sha256:… --python-version 3.12.x --out release-manifest.json   # push 後だけ。今は使えない

終了コード: 0=適合 / 1=検証 NG / 2=引数・入力の誤り
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import manifest, repo_facts


def _load(path: str):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="tools.release.cli")
    sub = p.add_subparsers(dest="cmd", required=True)
    pv = sub.add_parser("preview")
    pv.add_argument("--git-sha", required=True)
    pv.add_argument("--release")
    pv.add_argument("--built-at", required=True, help="UTC 秒まで（例 2026-11-01T00:00:00Z）。実行時刻ではなくコミット時刻を渡して再現性を保つ")
    pv.add_argument("--local-images", required=True)
    pv.add_argument("--python-version")
    pv.add_argument("--out", required=True)
    va = sub.add_parser("validate")
    va.add_argument("--kind", choices=["preview", "production"], required=True)
    va.add_argument("--expect-git-sha")
    va.add_argument("file")
    pr = sub.add_parser("production")
    pr.add_argument("--git-sha", required=True)
    pr.add_argument("--release", required=True)
    pr.add_argument("--built-at", required=True)
    pr.add_argument("--registry-images", required=True)
    pr.add_argument("--min-app-release", required=True)
    pr.add_argument("--feature-schema-hash", required=True)
    pr.add_argument("--python-version", required=True)
    pr.add_argument("--notes", default="")
    pr.add_argument("--out", required=True)
    try:
        a = p.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    root = repo_facts.repo_root()
    try:
        if a.cmd == "preview":
            sha = manifest.require_git_sha(a.git_sha)
            local = _load(a.local_images)
            if local.get("git_sha") != sha:
                print("::error::images-local.json の git_sha が一致しない", file=sys.stderr)
                return 2
            doc = manifest.build_preview(root, sha, a.release or f"preview-{sha[:12]}", a.built_at, local["images"], a.python_version)
            errors = manifest.validate_preview(doc, root, sha)
            if errors:
                print("プレビューの生成結果が検証に通らない:\n- " + "\n- ".join(errors), file=sys.stderr)
                return 1
            Path(a.out).write_text(manifest.dumps(doc), encoding="utf-8")
            print(f"wrote {a.out} (deployable=false, blockers={doc['blockers']})")
            return 0
        if a.cmd == "validate":
            doc = _load(a.file)
            fn = manifest.validate_production if a.kind == "production" else manifest.validate_preview
            errors = fn(doc, root, a.expect_git_sha)
            if errors:
                print(f"{a.file} は {a.kind} として不適合:\n- " + "\n- ".join(errors), file=sys.stderr)
                return 1
            print(f"{a.file}: {a.kind} として適合")
            return 0
        sha = manifest.require_git_sha(a.git_sha)
        doc = manifest.build_production(root, sha, a.release, a.built_at, _load(a.registry_images), a.min_app_release, a.feature_schema_hash, a.python_version, a.notes)
        Path(a.out).write_text(manifest.dumps(doc), encoding="utf-8")
        print(f"wrote {a.out}")
        return 0
    except (ValueError, OSError, json.JSONDecodeError, KeyError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1 if a.cmd == "production" and isinstance(e, ValueError) else 2


if __name__ == "__main__":
    sys.exit(main())
