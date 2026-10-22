#!/usr/bin/env bash
# 秘密情報・実データ・大きなファイルの混入を簡易検査する（CLAUDE.md 絶対ルール 5）。
# Git が追跡しているファイルだけを対象にする。違反があれば終了コード 1。
set -u
cd "$(git rev-parse --show-toplevel)"
fail=0
report() { echo "::error::$1"; fail=1; }

# 1. 追跡してはいけないファイル（拡張子/名前）
forbidden_re='(\.(sqlite3?|db|pkl|pickle|dump|pem|key|p12|jks|tfstate|tfvars|bak)$|\.sql\.gz$|\.csv\.gz$|(^|/)\.env(\..*)?$|(^|/)credentials.*\.json$|(^|/)service-account.*\.json$|(^|/)(build|\.gradle|\.kotlin)/)'
while IFS= read -r f; do
  report "追跡してはいけないファイル: $f"
done < <(git ls-files | grep -E "$forbidden_re" || true)

# 2. JV-Link の仕様書など PDF
while IFS= read -r f; do
  report "PDF は追跡しない（JV-Link SDK 資料は再配布不可）: $f"
done < <(git ls-files | grep -Ei '\.pdf$' || true)

# 3. 大きなファイル（wrapper jar を除き 2MB 超）
while IFS= read -r f; do
  [ -f "$f" ] || continue
  size=$(wc -c <"$f")
  if [ "$size" -gt 2097152 ] && [ "$f" != "gradle/wrapper/gradle-wrapper.jar" ]; then
    report "大きなファイル（${size} bytes）: $f"
  fi
done < <(git ls-files)

# 4. 追跡する jar は wrapper だけ
while IFS= read -r f; do
  [ "$f" = "gradle/wrapper/gradle-wrapper.jar" ] || report "jar は追跡しない: $f"
done < <(git ls-files | grep -E '\.jar$' || true)

# 5. 秘密っぽい文字列（このスクリプト自身は除外）
self=".github/scripts/check-repo-hygiene.sh"
patterns=(
  '-----BEGIN (RSA |EC |OPENSSH |DSA |)PRIVATE KEY-----'
  'https://(discord(app)?\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_-]{20,}'
  'AKIA[0-9A-Z]{16}'
  'AIza[0-9A-Za-z_-]{35}'
  'gh[pousr]_[A-Za-z0-9]{30,}'
  '"private_key"[[:space:]]*:'
)
for p in "${patterns[@]}"; do
  while IFS= read -r hit; do
    report "秘密らしい文字列: $hit"
  done < <(git grep -nIE -e "$p" -- . ":(exclude)$self" | cut -c1-120 || true)
done

if [ "$fail" -ne 0 ]; then
  echo "repo-hygiene: NG（上の項目を Git から除いてください。実データ・秘密は Git に含めません）"
  exit 1
fi
echo "repo-hygiene: OK"
