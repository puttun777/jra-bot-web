"""設定・契約の整合検査（push 前 / CI 共通）。『片方だけ直して食い違う』ことを機械的に検出する。

1. Flyway の最新版（db-migration の `V<n>__*.sql`）= 各アプリの `extra["requiredSchemaVersion"]`（bot / ingest / dashboard）。
2. `bot-run` の必須環境変数（`BotRunSettingsParser` の `raw/long/seconds("BOT_RUN_*")`）をすべて、イメージスモーク（`tools/release/image_smoke.py` の `BOT_RUN_ENV`）が渡している。
   （T3.8 で必須になったのにスモークが渡さず CI の images が落ちた事故の再発防止）
3. 永続化層（Doma の `@Table(name=…)`）が参照する表がすべて Flyway の `CREATE TABLE` に存在し、`R__grants.sql` のどこかの GRANT に現れる。
4. 兄弟リポジトリ `../jra-bot-infra` があれば、infra の `docs/CONTRACTS.md` が 1 の版と 2 の必須環境変数に言及している（無ければこの項目は『未実施』と表示する）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INFRA = ROOT.parent / "jra-bot-infra"


def flyway_latest(root: Path = ROOT) -> int:
    nums = [int(m.group(1)) for p in (root / "db-migration/src/main/resources/db/migration").glob("V*__*.sql") if (m := re.match(r"V(\d+)__", p.name))]
    if not nums:
        raise SystemExit("Flyway のマイグレーションが見つからない")
    return max(nums)


def app_schema_versions(root: Path = ROOT) -> dict[str, int]:
    out = {}
    for app in ("bot", "ingest", "dashboard"):
        t = (root / app / "build.gradle.kts").read_text(encoding="utf-8")
        m = re.search(r'extra\["requiredSchemaVersion"\]\s*=\s*"(\d+)"', t)
        if not m:
            raise SystemExit(f"{app}/build.gradle.kts に requiredSchemaVersion が無い")
        out[app] = int(m.group(1))
    return out


def required_bot_run_env(root: Path = ROOT) -> set[str]:
    t = (root / "bot/src/main/kotlin/jp/jrabot/bot/BotRunSettings.kt").read_text(encoding="utf-8")
    return set(re.findall(r'(?:raw|long|seconds)\(\s*"(BOT_RUN_[A-Z_]+)"', t))


def smoke_bot_run_env(root: Path = ROOT) -> set[str]:
    t = (root / "tools/release/image_smoke.py").read_text(encoding="utf-8")
    m = re.search(r"BOT_RUN_ENV\s*=\s*\{(.*?)\n\}", t, re.S)
    return set(re.findall(r'"(BOT_RUN_[A-Z_]+)"', m.group(1))) if m else set()


def doma_tables(root: Path = ROOT) -> set[str]:
    out: set[str] = set()
    for p in (root / "persistence/src/main/kotlin").rglob("*.kt"):
        out |= set(re.findall(r'@Table\(name = "([a-z_]+)"\)', p.read_text(encoding="utf-8")))
    return out


def created_tables(root: Path = ROOT) -> set[str]:
    out: set[str] = set()
    for p in (root / "db-migration/src/main/resources/db/migration").glob("V*__*.sql"):
        out |= set(re.findall(r"CREATE TABLE\s+`?([a-z_]+)`?", p.read_text(encoding="utf-8")))
    return out


def granted_tables(root: Path = ROOT) -> set[str]:
    t = (root / "db-migration/src/main/resources/db/migration/R__grants.sql").read_text(encoding="utf-8")
    return set(re.findall(r"`\$\{flyway:defaultSchema\}`\.`([a-z_]+)`", t))


def check(root: Path = ROOT, infra: Path | None = INFRA) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    notes: list[str] = []
    latest = flyway_latest(root)
    for app, v in app_schema_versions(root).items():
        if v != latest:
            errors.append(f"{app} の requiredSchemaVersion={v} が Flyway の最新版 V{latest} と違う")
    missing_env = required_bot_run_env(root) - smoke_bot_run_env(root)
    if missing_env:
        errors.append(f"bot-run の必須環境変数をイメージスモークが渡していない: {sorted(missing_env)}")
    if not required_bot_run_env(root):
        errors.append("BotRunSettings から必須環境変数を読み取れない（検査が空振りしている）")
    tables = doma_tables(root)
    for t in sorted(tables - created_tables(root)):
        errors.append(f"DAO の表 {t} が Flyway に無い")
    for t in sorted(tables - granted_tables(root)):
        errors.append(f"DAO の表 {t} が R__grants.sql のどの GRANT にも無い（権限が無く実行時に失敗する）")
    if infra is not None and (infra / "docs/CONTRACTS.md").exists():
        c = (infra / "docs/CONTRACTS.md").read_text(encoding="utf-8")
        for n in sorted(required_bot_run_env(root)):
            if n not in c:
                errors.append(f"infra の docs/CONTRACTS.md に {n} が無い（infra への同期漏れ）")
        m = re.search(r"現在 \*\*(\d+)\*\*", c)
        if m and int(m.group(1)) != latest:
            errors.append(f"infra の docs/CONTRACTS.md のスキーマ版 {m.group(1)} が Flyway の最新版 V{latest} と違う")
    else:
        notes.append("infra リポジトリ（../jra-bot-infra）が無いので infra 契約との整合は未実施")
    return errors, notes


def main() -> int:
    errors, notes = check()
    for n in notes:
        print(f"note: {n}")
    for e in errors:
        print(f"::error::{e}")
    print(f"consistency: {'OK' if not errors else 'NG'} (errors={len(errors)})")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
