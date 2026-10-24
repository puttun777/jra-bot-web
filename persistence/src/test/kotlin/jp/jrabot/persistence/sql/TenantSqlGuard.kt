package jp.jrabot.persistence.sql

/**
 * Doma の SQL テンプレートがテナントで限定されていることの検査規則。
 * - 対象の表はテナント別の表の一覧（[TENANT_TABLES]）。一覧に無い表を使う SQL は違反（新しい表を足すときに見直させる）。
 * - SELECT / UPDATE / DELETE: WHERE の中の AND 連結に `tenant_id = /* …tenantId */`（束縛）が、表の参照（FROM / JOIN / UPDATE）の数以上ある。
 *   OR で条件を緩めるもの、定数のテナント、`SET tenant_id = …`（行を別テナントへ移す）は違反。
 * - INSERT: 列の一覧に `tenant_id` があり、値が `/* …tenantId */` の束縛。
 */
internal object TenantSqlGuard {
    /** テナント条件を持たない SQL の許可リスト（ログイン時のテナント解決。email は全体で UNIQUE: D-063）。 */
    val ALLOWED_WITHOUT_TENANT = setOf("AppUserDao/selectByEmail.sql", "AgentDao/selectByAgentId.sql", "AgentNonceDao/deleteExpired.sql")

    val TENANT_TABLES =
        setOf(
            "races",
            "odds_snapshots",
            "bot_run",
            "signals",
            "race_schedule",
            "app_user",
            "race_day",
            "race_schedule_event",
            "calendar_override",
            "combo_odds_snapshots",
            "agent",
            "agent_nonce",
            "agent_heartbeat",
            "ingest_batch",
            "ingest_raw",
            "ingest_fetch",
            "ingest_cursor",
            "ingest_gap",
            "ingest_backfill",
            "ml_model",
            "horse_master_history",
            "race_horses",
            "horses",
            "horse_past_runs",
            "results",
        )

    val TENANT_BIND = Regex("""tenant_id\s*=\s*/\*\s*(?:entity\.|rows\.)?tenantId\s*\*/\s*[^\s,)]+""")
    private val TABLE_REF = Regex("""\b(?:FROM|JOIN|UPDATE|INTO)\s+([a-z_]+)""", RegexOption.IGNORE_CASE)
    private val WHERE = Regex("""\bWHERE\b""", RegexOption.IGNORE_CASE)
    private val SET_TENANT = Regex("""\bSET\b[^;]*?\btenant_id\s*=""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun violations(
        name: String,
        rawSql: String,
    ): List<String> {
        val sql = rawSql.lines().joinToString("\n") { it.substringBefore("-- ") }
        val statement = sql.trimStart().substringBefore(' ').uppercase()
        val problems = mutableListOf<String>()
        // `ON DUPLICATE KEY UPDATE` の UPDATE は表の参照ではない
        val tables =
            TABLE_REF
                .findAll(sql.replace(Regex("""ON\s+DUPLICATE\s+KEY\s+UPDATE""", RegexOption.IGNORE_CASE), "ON DUPLICATE KEY ASSIGN"))
                .map {
                    it.groupValues[1].lowercase()
                }.toList()
        for (t in tables.filter { it !in TENANT_TABLES }) problems += "$name: テナント別の表の一覧に無い表 '$t'（一覧とこの検査を見直す）"
        val tenantTableRefs = tables.count { it in TENANT_TABLES }
        if (tenantTableRefs == 0) problems += "$name: 表の参照が見つからない"

        if (statement == "INSERT") {
            val columns = sql.substringAfter('(', "").substringBefore(')')
            if (!Regex("""\btenant_id\b""").containsMatchIn(columns)) problems += "$name: INSERT の列に tenant_id が無い"
            if (!Regex("""/\*\s*(?:entity|rows)\.tenantId\s*\*/""").containsMatchIn(sql)) problems += "$name: INSERT の値に tenantId の束縛が無い"
            return problems
        }

        if (!WHERE.containsMatchIn(sql)) {
            problems += "$name: WHERE が無い（全件が対象になる）"
            return problems
        }
        val afterWhere = sql.substring(WHERE.find(sql)!!.range.first)
        val bindings = TENANT_BIND.findAll(afterWhere).count()
        if (bindings < tenantTableRefs) problems += "$name: tenant_id の束縛が $bindings 個（表の参照 $tenantTableRefs 個に対して不足）"
        if (Regex("""tenant_id\s*(?:=|<>|!=|<|>|\bIS\b|\bIN\b|\bLIKE\b)\s*+(?!/\*)""", RegexOption.IGNORE_CASE).containsMatchIn(afterWhere)) {
            problems += "$name: 束縛でない tenant_id の比較（定数・IS NULL・範囲など）がある"
        }
        if (Regex("""\bOR\b""", RegexOption.IGNORE_CASE).containsMatchIn(afterWhere)) problems += "$name: WHERE に OR がある（テナント条件が緩む恐れ。必要なら括弧の形を決めて許可する）"
        if (statement == "UPDATE" && SET_TENANT.containsMatchIn(sql.substringBefore(WHERE.find(sql)!!.value))) problems += "$name: SET で tenant_id を書き換えている"
        return problems
    }
}
