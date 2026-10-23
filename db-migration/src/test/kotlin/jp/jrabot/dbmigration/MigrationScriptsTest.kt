package jp.jrabot.dbmigration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * マイグレーション SQL の静的な検査（Docker 不要）。実際に MySQL へ適用して確かめるのは integration の統合テスト。
 * ここでは「規約から外れた SQL がレビューをすり抜けない」ことを機械的に守る。
 */
class MigrationScriptsTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$name")) { "$name が無い" }
            .readBytes()
            .toString(Charsets.UTF_8)

    private val v1 = resource("V1__baseline.sql")
    private val v2 = resource("V2__ops_calendar_ingest_ml.sql")
    private val v3 = resource("V3__ingest_auth_fetch_backfill.sql")
    private val grants = resource("R__grants.sql")

    /** コメント（`--` 以降）を除く。 */
    private fun code(sql: String): String = sql.lines().joinToString("\n") { it.substringBefore("--") }

    private val tableBlock = Regex("""CREATE TABLE (\w+) \((.*?)\n\) ENGINE""", RegexOption.DOT_MATCHES_ALL)

    private fun tables(sql: String): Map<String, String> = tableBlock.findAll(code(sql)).associate { it.groupValues[1] to it.groupValues[2] }

    private val expectedV1 =
        setOf(
            "tenant",
            "app_user",
            "code_venue",
            "races",
            "race_horses",
            "horses",
            "horse_past_runs",
            "odds_snapshots",
            "combo_odds_snapshots",
            "results",
            "payouts",
            "signals",
            "bets",
            "bet_legs",
            "race_evaluations",
            "ticket_evaluations",
            "model_metrics",
            "ingest_cursor",
            "import_quarantine",
            "audit_log",
        )
    private val expectedV2 =
        setOf(
            "race_day",
            "race_schedule",
            "race_schedule_event",
            "calendar_override",
            "bot_run",
            "agent",
            "agent_registration_code",
            "agent_heartbeat",
            "agent_nonce",
            "ingest_batch",
            "ingest_raw",
            "ingest_gap",
            "ml_model",
        )

    @Test
    fun `V1 と V2 は ARCHITECTURE 5_1 の表だけを作る（設計に無い表を足さない）`() {
        assertEquals(expectedV1, tables(v1).keys)
        assertEquals(expectedV2, tables(v2).keys)
        assertEquals(emptySet(), tables(v1).keys.intersect(tables(v2).keys))
        // V3（T3.4）は V1/V2 の表を作り直さず、新しい 2 表だけを足す（既存の表への変更は ALTER）
        assertEquals(setOf("ingest_fetch", "ingest_backfill"), tables(v3).keys)
        assertEquals(emptySet(), tables(v3).keys.intersect(tables(v1).keys + tables(v2).keys))
    }

    @Test
    fun `すべての表は InnoDB と utf8mb4`() {
        val all = code(v1) + code(v2) + code(v3)
        assertEquals(35, Regex("CREATE TABLE ").findAll(all).count())
        assertEquals(35, Regex("""\) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;""").findAll(all).count())
    }

    @Test
    fun `テナント表は tenant_id が先頭の列`() {
        val shared = setOf("tenant", "code_venue")
        for ((name, body) in tables(v1) + tables(v2) + tables(v3)) {
            if (name in shared) continue
            val firstColumn = body.lines().first { it.isNotBlank() }.trim()
            assertTrue(firstColumn.startsWith("id ") || firstColumn.startsWith("tenant_id "), "$name の先頭の列は tenant_id（または代理キー id）: $firstColumn")
            assertTrue(Regex("""\btenant_id\s+BIGINT\s+NOT NULL""").containsMatchIn(body), "$name に tenant_id BIGINT NOT NULL が無い")
        }
    }

    @Test
    fun `テナント表を参照する FK はすべて tenant_id を先頭にした複合 FK`() {
        val fk = Regex("""FOREIGN KEY \(([^)]*)\) REFERENCES (\w+) \(([^)]*)\)""")
        var checked = 0
        for ((name, body) in tables(v1) + tables(v2) + tables(v3)) {
            for (m in fk.findAll(body)) {
                val (childCols, parent, parentCols) = m.destructured
                if (parent == "code_venue" || parent == "tenant") continue
                checked++
                if (name == "ml_model" && parent == "ml_model") {
                    // 自己参照 FK だけは列順を変える（MySQL 9.7.4 が (tenant_id, x) → (tenant_id, y) の自己参照 FK を検査しないため: D-069）
                    assertTrue("tenant_id" in childCols && "tenant_id" in parentCols, "$name → $parent: tenant_id を含む ($childCols / $parentCols)")
                    assertEquals(childCols.split(", ").indexOf("tenant_id"), parentCols.split(", ").indexOf("tenant_id"), "tenant_id の位置が一致する")
                    assertFalse(childCols.startsWith("tenant_id,"), "自己参照 FK は tenant_id を先頭にしない（D-069）")
                    continue
                }
                assertTrue(childCols.startsWith("tenant_id,"), "$name → $parent: 子の FK 列は tenant_id から始まる ($childCols)")
                assertTrue(parentCols.startsWith("tenant_id,"), "$name → $parent: 親の参照列は tenant_id から始まる ($parentCols)")
            }
        }
        assertTrue(checked >= 20, "複合 FK を $checked 件しか検出できない（パターン不一致？）")
    }

    @Test
    fun `CASCADE は設計どおり bet_legs と ticket_evaluations だけ`() {
        val withCascade = (tables(v1) + tables(v2)).filterValues { "CASCADE" in it }.keys
        assertEquals(setOf("bet_legs", "ticket_evaluations"), withCascade)
        assertFalse("SET NULL" in code(v1) + code(v2))
    }

    @Test
    fun `V1 と V2 に本番の行・ユーザー・破壊的な文を入れない`() {
        for (sql in listOf(code(v1), code(v2), code(v3))) {
            for (forbidden in listOf("CREATE USER", "DROP", "TRUNCATE", "DELETE", "UPDATE", "GRANT", "REVOKE", "CREATE DATABASE", "CREATE SCHEMA")) {
                assertFalse(Regex("""(?m)^\s*$forbidden\b""").containsMatchIn(sql), "$forbidden は禁止")
            }
            val inserts = Regex("""INSERT INTO (\w+)""").findAll(sql).map { it.groupValues[1] }.toSet()
            assertTrue(inserts.all { it == "code_venue" }, "INSERT は事実の定数 code_venue だけ: $inserts")
        }
        assertFalse(Regex("INSERT INTO (tenant|app_user)").containsMatchIn(code(v1) + code(v2)), "V1/V2 でテナントと利用者の行を作らない")
    }

    @Test
    fun `コード列は ascii_bin で、予約語で壊れる列名を使わない`() {
        val all = tables(v1) + tables(v2) + tables(v3)
        val raceIdLines = all.values.flatMap { it.lines() }.filter { Regex("""^\s*race_id\s+CHAR""").containsMatchIn(it) }
        assertTrue(raceIdLines.isNotEmpty())
        for (line in raceIdLines) assertTrue("CHAR(12) CHARACTER SET ascii COLLATE ascii_bin" in line, "race_id は CHAR(12) ascii_bin: $line")
        val columns = all.values.flatMap { it.lines() }.map { it.trim().substringBefore(' ') }
        for (reserved in listOf("key", "rank", "count", "date", "option", "class", "order", "group", "index")) {
            assertFalse(reserved in columns, "予約語の列名: $reserved")
        }
    }

    // ---------------- R__grants.sql ----------------
    private val grantsCode = code(grants)
    private val statements = grantsCode.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    private val users = listOf("app", "ingest", "bot", "mltrain")
    private val allTables = tables(v1).keys + tables(v2).keys + tables(v3).keys + "flyway_schema_history"

    @Test
    fun `grants は GRANT と REVOKE だけで、スキーマ名はプレースホルダ`() {
        assertTrue(statements.size > 100)
        for (s in statements) {
            assertTrue(s.startsWith("GRANT ") || s.startsWith("REVOKE "), "GRANT/REVOKE 以外の文: $s")
            assertTrue("`\${flyway:defaultSchema}`" in s, "スキーマ名は placeholder で書く: $s")
            assertFalse("*.*" in s, "グローバル権限は付けない: $s")
        }
        assertFalse(Regex("""\$\{(?!flyway:defaultSchema\})""").containsMatchIn(grantsCode), "想定外の placeholder")
    }

    /** 付与セクション（リセットの後）の GRANT 文。 */
    private val grantSection = code(grants.substringAfter("===== 付与: app ====="))
    private val grantStatements = grantSection.split(';').map { it.trim() }.filter { it.startsWith("GRANT ") }

    @Test
    fun `grants は migrate の権限に触れず、強い権限や GRANT OPTION を付けない`() {
        assertFalse("'migrate'" in grantsCode)
        assertFalse("GRANT OPTION" in grantsCode.uppercase())
        assertTrue(grantStatements.size > 50)
        val forbiddenPrivileges =
            listOf("ALL", "DROP", "ALTER", "CREATE", "INDEX", "REFERENCES", "TRIGGER", "EXECUTE", "SUPER", "FILE", "PROCESS", "LOCK TABLES", "SHOW VIEW")
        for (s in grantStatements) {
            // 列リスト（SELECT (a, b) の括弧）を除いて権限名だけを見る
            val privileges = s.removePrefix("GRANT ").substringBefore(" ON ").replace(Regex("\\([^)]*\\)"), "")
            for (p in forbiddenPrivileges) {
                assertFalse(Regex("""\b$p\b""").containsMatchIn(privileges), "禁止の権限 $p: $s")
            }
        }
    }

    @Test
    fun `各ユーザーについて全テーブルのリセットがある（新しい表の付け忘れを検出）`() {
        for (user in users) {
            for (table in allTables) {
                val reset = "REVOKE ALL PRIVILEGES ON `\${flyway:defaultSchema}`.`$table` FROM '$user'@'%'"
                assertTrue(reset in statements, "$user の $table のリセットが無い")
            }
            assertTrue("REVOKE ALL PRIVILEGES ON `\${flyway:defaultSchema}`.* FROM '$user'@'%'" in statements, "$user のスキーマ全体のリセットが無い")
        }
    }

    @Test
    fun `GRANT の対象は実在するテーブルだけで、ユーザーは 4 つだけ`() {
        val target = Regex("""ON `\$\{flyway:defaultSchema}`\.`(\w+)` TO '(\w+)'@'%'""")
        for (s in statements.filter { it.startsWith("GRANT ") && "`.`" in it }) {
            val m = checkNotNull(target.find(s)) { "解釈できない GRANT: $s" }
            assertTrue(m.groupValues[1] in allTables, "存在しない表: $s")
            assertTrue(m.groupValues[2] in users, "想定外のユーザー: $s")
        }
    }

    @Test
    fun `mltrain は ml_model の SELECT と INSERT だけ`() {
        val toMltrain = grantStatements.filter { it.endsWith("TO 'mltrain'@'%'") }
        assertEquals(listOf("GRANT SELECT, INSERT ON `\${flyway:defaultSchema}`.`ml_model` TO 'mltrain'@'%'"), toMltrain)
    }

    @Test
    fun `app は書込みできる範囲が契約の管理操作の表だけ`() {
        val writes =
            grantStatements
                .filter { it.endsWith("TO 'app'@'%'") && !it.startsWith("GRANT SELECT ") }
                .map { it.substringAfter("`.`").substringBefore("`") + ":" + it.removePrefix("GRANT ").substringBefore(" ON ") }
                .toSet()
        assertEquals(
            setOf(
                "calendar_override:INSERT, UPDATE",
                "race_schedule_event:INSERT",
                "agent_registration_code:INSERT",
                "agent:UPDATE (status, revoked_at, revoked_by)",
                "audit_log:INSERT",
                "bot_run:INSERT",
                "ml_model:UPDATE (status, active_flag, activated_at, activated_by, previous_model_id)",
            ),
            writes,
        )
    }
}
