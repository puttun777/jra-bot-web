package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V5（評価結果の一意性・通知タイミングの確定）を、**旧データのある DB に適用する**検証（合成データ。MySQL コンテナ。Cloud SQL ではない）。
 * V5 適用前の状態は、全部を適用した DB から V5 の変更だけを戻して再現する（V1〜V4 の SQL を複製しない）。
 */
@Tag("integration")
class V5MigrationIntegrationTest {
    private val now = "UTC_TIMESTAMP(6)"
    private val race = "202611010501"
    private val at = "'2026-11-01 01:00:00.000000'"

    private fun hash(n: Int) = n.toString().padStart(64, '0')

    /** 全部適用 → V5 の変更だけを戻す → 旧データを入れる。 */
    private fun preV5(): TestDb {
        val db = CliHarness.migratedDatabase()
        db.exec("DROP TABLE race_timing_notice")
        db.exec("ALTER TABLE race_evaluations DROP INDEX uq_race_evaluations_native, DROP COLUMN uq_native")
        db.exec("DELETE FROM flyway_schema_history WHERE version = '5'")
        db.root().use { c ->
            c.run("INSERT INTO tenant (tenant_id, name, created_at) VALUES (1, 't1', $now), (2, 't2', $now)")
            for (t in 1..2) {
                c.run("INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES ($t, '$race', '2026-11-01', '05', 1, $now, $now)")
            }
            // 旧データ（legacy_id あり）の重複: 同じ (tenant, race, snapshot_at) が 3 行
            for (id in 101..103) {
                c.run("INSERT INTO race_evaluations (tenant_id, legacy_id, race_id, snapshot_at, created_at) VALUES (1, $id, '$race', $at, $now)")
            }
        }
        return db
    }

    private fun evalCount(
        db: TestDb,
        where: String = "1 = 1",
    ) = db.scalar("SELECT COUNT(*) FROM race_evaluations WHERE $where")!!.toInt()

    @Test
    fun `旧データの重複を含む DB に V5 を適用できる 旧行は変更も削除もされない`() {
        val db = preV5()
        // 新方式の行（legacy_id なし）が 1 件（重複なし）
        db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', '2026-11-01 02:00:00.000000', $now)")
        val before = db.query("SELECT id, legacy_id FROM race_evaluations ORDER BY id") { "${it.getLong(1)}|${it.getObject(2)}" }
        val result = CliHarness.run(db, "migrate")
        assertEquals(0, result.code, result.stdout + result.stderr)
        assertTrue(""""applied":["5","R__grants"]""" in result.stdout || "\"5\"" in result.stdout, result.stdout)
        assertEquals(before, db.query("SELECT id, legacy_id FROM race_evaluations ORDER BY id") { "${it.getLong(1)}|${it.getObject(2)}" }, "既存の行は変わらない")
        assertEquals(3, evalCount(db, "legacy_id IS NOT NULL"), "旧データの重複はそのまま残る")
        // 旧データには UNIQUE が掛からない（さらに同じキーの旧行を足せる）
        db.exec("INSERT INTO race_evaluations (tenant_id, legacy_id, race_id, snapshot_at, created_at) VALUES (1, 104, '$race', $at, $now)")
        assertEquals(4, evalCount(db, "legacy_id IS NOT NULL"))
    }

    @Test
    fun `新方式の行だけが UNIQUE 観測時刻が違えば別 テナントが違えば別 ON DUPLICATE KEY は何も変えない`() {
        val db = preV5()
        assertEquals(0, CliHarness.run(db, "migrate").code)
        db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', $at, $now)")
        assertSqlError(MySqlError.DUPLICATE_ENTRY) {
            db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', $at, $now)")
        }
        db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', '2026-11-01 01:00:01.000000', $now)")
        db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (2, '$race', $at, $now)")
        db.root().use { c ->
            val n = c.update("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', $at, $now) ON DUPLICATE KEY UPDATE tenant_id = tenant_id")
            assertTrue(n == 0 || n == 1, "既存なら変更なし（クライアントの設定で 0）")
        }
        assertEquals(3, evalCount(db, "legacy_id IS NULL"))
    }

    @Test
    fun `新方式の行に重複があると V5 は失敗し 何も変更しない 自動では削除しない`() {
        val db = preV5()
        db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', $at, $now), (1, '$race', $at, $now)")
        val rowsBefore = evalCount(db)
        val result = CliHarness.run(db, "migrate")
        assertEquals(1, result.code, "重複があれば失敗（人が判断する）")
        assertEquals(rowsBefore, evalCount(db), "行は削除しない")
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'race_timing_notice'"), "表だけが残る中途半端な状態にしない")
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'race_evaluations' AND COLUMN_NAME = 'uq_native'"))
        // 検査用のクエリ（V5 のコメントに書いた事前確認）で重複が見つかる
        assertEquals("1", db.scalar("SELECT COUNT(*) FROM (SELECT 1 FROM race_evaluations WHERE legacy_id IS NULL GROUP BY tenant_id, race_id, snapshot_at HAVING COUNT(*) > 1) d"))
    }

    @Test
    fun `race_timing_notice は PK で同じタイミングを拒否し 範囲と FK を守り 追記専用の権限になる`() {
        val db = preV5()
        assertEquals(0, CliHarness.run(db, "migrate").code)
        db.exec("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race', $at, $now)")
        val ev = db.scalar("SELECT id FROM race_evaluations WHERE legacy_id IS NULL")!!
        val sha = hash(7)

        fun ins(
            tenant: Int = 1,
            centi: Int = 500,
            evalId: String = ev,
            sha256: String = sha,
        ) = "INSERT INTO race_timing_notice (tenant_id, race_id, timing_centi, run_id, evaluation_id, odds_observed_at, notice_schema, notice_json, notice_sha256, fired_at) " +
            "VALUES ($tenant, '$race', $centi, '00000000-0000-4000-8000-000000000001', $evalId, $at, 1, '{\"items\":[]}', '$sha256', $now)"
        db.exec(ins())
        assertSqlError(MySqlError.DUPLICATE_ENTRY, message = "同じレース・同じタイミング") { db.exec(ins()) }
        db.exec(ins(centi = 3000))
        assertSqlError(MySqlError.CHECK_VIOLATED, message = "0 は範囲外") { db.exec(ins(centi = 0)) }
        assertSqlError(MySqlError.CHECK_VIOLATED, message = "1440 分を超える") { db.exec(ins(centi = 144001)) }
        assertSqlError(MySqlError.CHECK_VIOLATED, message = "ハッシュの形式") { db.exec(ins(centi = 600, sha256 = "XYZ")) }
        assertSqlError(MySqlError.FK_CHILD, message = "評価結果が無い") { db.exec(ins(centi = 700, evalId = "999999")) }
        assertSqlError(MySqlError.FK_CHILD, message = "レースが無い（テナント 3）") { db.exec(ins(tenant = 3, centi = 700)) }

        // GRANT: bot は SELECT と INSERT だけ。app は SELECT だけ。ingest・mltrain は何も読めない
        db.connect("bot").use { c ->
            assertEquals(2, c.long("SELECT COUNT(*) FROM race_timing_notice"))
            c.update(ins(centi = 800).replace("race_timing_notice", "race_timing_notice"))
            assertSqlError(MySqlError.TABLE_DENIED, MySqlError.COLUMN_DENIED, message = "bot は UPDATE できない") { c.update("UPDATE race_timing_notice SET notice_schema = 2") }
            assertSqlError(MySqlError.TABLE_DENIED, message = "bot は DELETE できない") { c.update("DELETE FROM race_timing_notice") }
        }
        db.connect("app").use { c ->
            assertEquals(3, c.long("SELECT COUNT(*) FROM race_timing_notice"))
            assertSqlError(MySqlError.TABLE_DENIED, message = "app は INSERT できない") { c.update(ins(centi = 900)) }
            assertSqlError(MySqlError.TABLE_DENIED, MySqlError.COLUMN_DENIED, message = "app は UPDATE できない") { c.update("UPDATE race_timing_notice SET notice_schema = 2") }
            assertSqlError(MySqlError.TABLE_DENIED, message = "app は DELETE できない") { c.update("DELETE FROM race_timing_notice") }
        }
        for (u in listOf("ingest", "mltrain")) {
            db.connect(u).use { c -> assertSqlError(MySqlError.TABLE_DENIED, message = "$u は読めない") { c.long("SELECT COUNT(*) FROM race_timing_notice") } }
        }
    }

    @Test
    fun `V5 を適用済みの DB への再度の migrate は何もしない 履歴に V5 が成功で 1 行だけ`() {
        val db = preV5()
        assertEquals(0, CliHarness.run(db, "migrate").code)
        val again = CliHarness.run(db, "migrate")
        assertEquals(0, again.code, again.stdout)
        assertEquals("1", db.scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = 1"))
        assertEquals(1, CliHarness.run(db, "validate").let { if (it.code == 0) 1 else 0 })
    }
}
