package jp.jrabot.domain.contract

import jp.jrabot.domain.agent.HeartbeatMirror
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleEventRepository
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.dataset.DatasetSource
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.HorseMasterHistoryStore
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * テナント境界の契約（DATA_MIGRATION_DESIGN §4.1 ②）: テナントのデータを扱うポートの操作は、すべて
 * 第一引数が [TenantId] か [TenantScoped]（テナントを持つモデル）で、テナントをまたぐ全件取得を型として提供しない。
 * 例外は [AppUserRepository.findByEmail]（認証直後にテナントを解決する、メールアドレス 1 件の完全一致）だけ。
 * Repository の実装（T2.4）は、同じ規則の越境テスト（テナント 2 つ）で検証する。
 */
class TenantBoundaryContractTest {
    private val tenantPorts: List<Class<*>> =
        listOf(
            RaceRepository::class.java,
            RaceScheduleRepository::class.java,
            RaceDayRepository::class.java,
            ScheduleEventRepository::class.java,
            CalendarOverrideRepository::class.java,
            ScheduleFetchFactReader::class.java,
            OddsSnapshotRepository::class.java,
            SignalRepository::class.java,
            BotRunRepository::class.java,
            AppUserRepository::class.java,
            ModelStore::class.java,
            DatasetStore::class.java,
            FeatureSource::class.java,
            DatasetSource::class.java,
            HorseMasterHistoryStore::class.java,
            HeartbeatMirror::class.java,
        )

    private fun operations(port: Class<*>): List<Method> =
        port.methods.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.declaringClass != Any::class.java }

    private fun isTenantScoped(method: Method): Boolean {
        val first = method.parameterTypes.firstOrNull() ?: return false
        return first == TenantId::class.java || TenantScoped::class.java.isAssignableFrom(first)
    }

    @Test
    fun `すべての操作がテナントを必須にする（例外は findByEmail だけ）`() {
        val exceptions =
            tenantPorts
                .flatMap { port -> operations(port).filterNot(::isTenantScoped).map { "${port.simpleName}.${it.name}" } }
                .map { it.substringBefore('-') } // 値クラスの引数を持つ関数は名前が `名前-ハッシュ` になる
        assertEquals(listOf("AppUserRepository.findByEmail"), exceptions)
    }

    @Test
    fun `引数なしや全件取得の操作がない`() {
        for (port in tenantPorts) {
            for (m in operations(port)) {
                assertTrue(m.parameterCount >= 1, "${port.simpleName}.${m.name} は引数なし")
                assertTrue(!Regex("(?i)^(find|list|get|count|delete)?(all|every)").containsMatchIn(m.name), "${port.simpleName}.${m.name}")
            }
        }
    }

    @Test
    fun `ポートの型にドメイン外の型（Entity など）が出てこない`() {
        val qualified = Regex("""[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)+""")
        val allowed = listOf("jp.jrabot.domain.", "java.lang.", "java.util.", "java.time.", "java.io.", "kotlin.")
        for (port in tenantPorts + JobLauncher::class.java) {
            for (m in operations(port)) {
                val types = (listOf(m.genericReturnType) + m.genericParameterTypes).flatMap { t -> qualified.findAll(t.typeName).map { it.value }.toList() }
                for (t in types) assertTrue(allowed.any { t.startsWith(it) }, "${port.simpleName}.${m.name} の型 $t はドメイン外")
            }
        }
    }
}
