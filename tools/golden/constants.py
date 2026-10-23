"""固定値。参照元コミット・スキーマ版・許容誤差を変更する場合は docs/DECISIONS.md に理由を記録すること。"""
from pathlib import Path

TOOL_VERSION = "0.1.0"
SCHEMA_VERSION = 1
SUPPORTED_SCHEMA_VERSIONS = (1,)

SOURCE_REPOSITORY = "puttun777/jra-bot"
# 短縮 SHA f7dbf69 を GitHub 上で解決した完全 SHA。main / HEAD は正本にしない。
PINNED_COMMIT = "f7dbf69fd8940121782870de902e8c449e6ca149"

# 生成時に要求する numpy（tools/golden/requirements.txt と一致させる）
PINNED_NUMPY = "2.5.3"

SEED = 20260101
TIMEZONE = "Asia/Tokyo"
FIXED_TIME = "2026-01-01T00:00:00+09:00"

GOLDEN_DIR = Path(__file__).resolve().parent
FIXTURE_DIR = GOLDEN_DIR / "fixtures"
MANIFEST_NAME = "manifest.json"

# 比較の既定（docs/ML_DESIGN.md §7.2 / MIGRATION_MATRIX §7.4）
TOL_PROB = 1e-9
TOL_NORMALIZED = 1e-12
