package jp.jrabot.persistence.sql

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQL テンプレートの静的検査（Docker 不要）。テナント別の表を扱う SQL は、必ず `tenant_id` を条件（WHERE / INSERT の列と値）に持つ。
 * MySQL に行単位のアクセス制御は無いので、SQL から `tenant_id` 条件が外れたら**ここで CI が失敗する**。
 * 検査そのものも壊れていないことを、実ファイルから条件を取り除く変異で確かめる（`検査は…` のテスト）。
 */
class SqlTemplateTenantGuardTest {
    private val sqlRoot = File("src/main/resources/META-INF/jp/jrabot/persistence/dao")

    private fun sqlFiles(): List<File> =
        sqlRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .sortedBy { it.path }
            .toList()

    private fun File.key(): String = relativeTo(sqlRoot).path

    @Test
    fun `SQL テンプレートが見つかる（検査が空振りしていない）`() {
        assertTrue(sqlRoot.isDirectory, "SQL のディレクトリが無い: ${sqlRoot.absolutePath}")
        assertTrue(sqlFiles().size >= 25, "SQL テンプレートが少なすぎる: ${sqlFiles().size}")
    }

    @Test
    fun `全ての SQL テンプレートが tenant_id を必須にしている（許可リストの 1 本を除く）`() {
        val violations =
            sqlFiles().filter { it.key() !in TenantSqlGuard.ALLOWED_WITHOUT_TENANT }.flatMap { f ->
                TenantSqlGuard.violations(f.key(), f.readText())
            }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `テナント条件の例外は3本だけ（ログインのemail解決・認証のagent_id解決・nonce掃除）で、実際にテナント条件を持たない`() {
        assertEquals(
            setOf("AppUserDao/selectByEmail.sql", "AgentDao/selectByAgentId.sql", "AgentNonceDao/deleteExpired.sql"),
            TenantSqlGuard.ALLOWED_WITHOUT_TENANT,
        )
        for (name in TenantSqlGuard.ALLOWED_WITHOUT_TENANT) {
            assertTrue(!File(sqlRoot, name).readText().contains("tenant_id"), "許可リストの SQL がテナント条件を持っている（リストが古い）: $name")
        }
        assertTrue(File(sqlRoot, "AppUserDao/selectByEmail.sql").readText().contains("email = /* email */"), "完全一致の 1 件取得であること")
        // agent_id は V3 の UNIQUE（全体で一意）なので、1 件に決まる
        assertTrue(File(sqlRoot, "AgentDao/selectByAgentId.sql").readText().contains("agent_id = /* agentId */"), "agent_id の完全一致であること")
    }

    @Test
    fun `検査は、実ファイルから tenant_id の条件を 1 つ外すと必ず違反を報告する`() {
        for (f in sqlFiles().filter { it.key() !in TenantSqlGuard.ALLOWED_WITHOUT_TENANT }) {
            val original = f.readText()
            val mutations =
                if (original.trimStart().startsWith("INSERT", ignoreCase = true)) {
                    // INSERT は列の一覧から tenant_id を外す／値の束縛を外す、の両方を検査する
                    listOf(
                        original.replaceFirst("tenant_id,", ""),
                        original.replaceFirst(Regex("""/\*\s*(?:entity|rows)\.tenantId\s*\*/\s*\d+\s*,"""), ""),
                    )
                } else {
                    val bindings = TenantSqlGuard.TENANT_BIND.findAll(original).toList()
                    assertTrue(bindings.isNotEmpty(), "${f.key()} にテナントの束縛が無い")
                    bindings.map { original.removeRange(it.range) + " " }
                }
            for ((i, mutated) in mutations.withIndex()) {
                assertTrue(mutated != original, "${f.key()}: 変異 #$i が元と同じ")
                assertTrue(TenantSqlGuard.violations(f.key(), mutated).isNotEmpty(), "${f.key()}: 変異 #$i（テナント条件を外した SQL）を検査が通してしまう")
            }
        }
    }

    @Test
    fun `検査は、UPDATE の SET で tenant_id を書き換える・未知の表を使う・WHERE の無い SQL を拒否する`() {
        val ok = "UPDATE bot_run SET state = /* entity.state */'x' WHERE tenant_id = /* entity.tenantId */0 AND run_id = /* entity.runId */'x'"
        assertEquals(emptyList(), TenantSqlGuard.violations("t.sql", ok))
        assertTrue(TenantSqlGuard.violations("t.sql", ok.replace("SET state", "SET tenant_id = 2, state")).isNotEmpty())
        assertTrue(TenantSqlGuard.violations("t.sql", "SELECT * FROM unknown_table WHERE tenant_id = /* tenantId */0").isNotEmpty())
        assertTrue(TenantSqlGuard.violations("t.sql", "SELECT * FROM races").isNotEmpty())
        assertTrue(TenantSqlGuard.violations("t.sql", "DELETE FROM races").isNotEmpty())
        // 条件が OR で緩められている
        assertTrue(TenantSqlGuard.violations("t.sql", "SELECT * FROM races WHERE tenant_id = /* tenantId */0 OR race_id = /* raceId */'x'").isNotEmpty())
        // 定数のテナント(束縛でない)
        assertTrue(TenantSqlGuard.violations("t.sql", "SELECT * FROM races WHERE tenant_id = 1").isNotEmpty())
        // INSERT で tenant_id 列が無い
        assertTrue(TenantSqlGuard.violations("t.sql", "INSERT INTO races (race_id) VALUES (/* entity.raceId */'x')").isNotEmpty())
    }

    @Test
    fun `Doma の DAO インターフェースは全メソッドに対応する SQL ファイルを持つ（SQL ファイル方式のメソッド）`() {
        val daoNames =
            sqlRoot
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .map { it.name }
                .sorted()
        assertEquals(
            listOf(
                "AgentDao",
                "AgentHeartbeatDao",
                "AgentNonceDao",
                "AppUserDao",
                "BotRunDao",
                "CalendarOverrideDao",
                "ComboOddsSnapshotDao",
                "HorseMasterHistoryDao",
                "IngestBackfillDao",
                "IngestBatchDao",
                "IngestCursorDao",
                "IngestFetchDao",
                "IngestGapDao",
                "IngestRawDao",
                "MlModelDao",
                "MlSourceDao",
                "OddsSnapshotDao",
                "PayoutDao",
                "RaceDao",
                "RaceDayDao",
                "RaceEvaluationDao",
                "RaceHorseNameDao",
                "RaceScheduleDao",
                "RaceTimingNoticeDao",
                "ResultDao",
                "RetentionDao",
                "ScheduleEventDao",
                "SignalDao",
            ),
            daoNames,
        )
    }
}
