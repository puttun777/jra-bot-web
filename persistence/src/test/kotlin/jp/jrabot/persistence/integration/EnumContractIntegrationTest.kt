package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.tenant.AppUserRole
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** DDL の ENUM の値と、ドメインの列挙の名前が（順序は問わず）完全に一致すること。食い違うと読み戻しで例外になるため、ここで先に検出する。 */
@Tag("integration")
class EnumContractIntegrationTest {
    private val db = MySqlSupport.migratedDatabase()

    private fun enumValues(
        table: String,
        column: String,
    ): Set<String> {
        val type =
            checkNotNull(
                db.scalar(
                    "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = '$table' AND COLUMN_NAME = '$column'",
                ),
            ) { "$table.$column が無い" }
        check(type.startsWith("enum(")) { "$table.$column は ENUM ではない: $type" }
        return Regex("'([^']*)'").findAll(type).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `bot_run の state と trigger_type はドメインの BotRunState・BotRunTrigger と一致する`() {
        assertEquals(BotRunState.entries.map { it.name }.toSet(), enumValues("bot_run", "state"))
        assertEquals(BotRunTrigger.entries.map { it.name }.toSet(), enumValues("bot_run", "trigger_type"))
    }

    @Test
    fun `signals の notify_state は NotificationState と一致する`() {
        assertEquals(NotificationState.entries.map { it.name }.toSet(), enumValues("signals", "notify_state"))
    }

    @Test
    fun `app_user の role は AppUserRole と一致する`() {
        assertEquals(AppUserRole.entries.map { it.name }.toSet(), enumValues("app_user", "role"))
    }

    @Test
    fun `race_schedule の status は RaceScheduleStatus と一致する`() {
        assertEquals(RaceScheduleStatus.entries.map { it.name }.toSet(), enumValues("race_schedule", "status"))
    }

    @Test
    fun `市場(market)の ENUM は Market の code と一致する（オッズ・Signal・払戻など全ての表）`() {
        val expected = Market.entries.map { it.code }.toSet()
        for (table in listOf("odds_snapshots", "combo_odds_snapshots", "signals", "payouts", "bets", "model_metrics")) {
            assertEquals(expected, enumValues(table, "market"), "$table.market")
        }
    }

    @Test
    fun `bot_run の排他の状態は、ドメインの holdsActiveSlot と DDL の CHECK で一致する`() {
        val holding =
            BotRunState.entries
                .filter { it.holdsActiveSlot }
                .map { it.name }
                .toSet()
        assertEquals(setOf("STARTING", "RUNNING", "DRAINING"), holding)
        val check =
            checkNotNull(
                db.scalar(
                    "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '${db.name}' AND CONSTRAINT_NAME = 'ck_bot_run_slot'",
                ),
            )
        for (name in holding) assertEquals(true, check.contains(name), "ck_bot_run_slot に $name が無い: $check")
    }
}
