package jp.jrabot.bot

import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.botrun.BotRunConfig
import jp.jrabot.domain.botrun.HitRateSchedule
import jp.jrabot.domain.botrun.PollingIntervals
import jp.jrabot.domain.botrun.ResultWaitConfig
import jp.jrabot.domain.botrun.SnapshotPolicy
import jp.jrabot.domain.botrun.TightenMode
import jp.jrabot.domain.decision.PriorityMode
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ops.AppEnv
import java.time.Duration

/** `bot-run` だけが使う設定（環境変数）。[run] は業務ループの設定、[modelStoreUri] / [modelVerifyPubKey] は ML を有効にするときだけ non-null。 */
class BotRunSettings(
    val run: BotRunConfig,
    val modelStoreUri: String?,
    val modelVerifyPubKey: String?,
)

/**
 * `bot-run` の環境変数（CONTRACTS §2。0.4.12）。**運用値の既定を持たない**: 未設定・範囲外は [IllegalStateException]（メッセージは変数名だけ。値を含めない。終了コード 2）。
 * 監視ウィンドウの 4 つ（`CALENDAR_*`）は calendar-check と共通（[CalendarSettingsParser]）。
 *
 * | 変数 | 意味 |
 * |---|---|
 * | `BOT_RUN_LEASE_SECONDS` / `BOT_RUN_HEARTBEAT_SECONDS` | リース期限（10〜3600 秒）/ heartbeat の間隔（期限より短い。設計の案は 300 / 60） |
 * | `BOT_RUN_POLL_FAR_SECONDS` / `_MID_` / `_NEAR_` | ポーリング間隔（発走まで 30 分超 / 5 分超 / それ以下。1〜3600 秒。旧の既定は 300 / 60 / 30） |
 * | `BOT_RUN_MAX_ODDS_AGE_SECONDS` | オッズの鮮度の上限（1〜86400 秒。設計の案は 180） |
 * | `BOT_RUN_MAX_RUNTIME_MINUTES` | 最大稼働（1〜840 分。契約の上限は 14 時間） |
 * | `BOT_RUN_STALE_CLAIM_SECONDS` | 起動時に回収する古い claim の古さ（60〜86400 秒） |
 * | `BOT_RUN_MAX_CYCLE_FAILURES` | 連続して失敗したサイクルの上限（1〜20） |
 * | `BOT_RUN_SNAPSHOT_MINUTES` | 評価・通知のタイミング（発走の何分前か。カンマ区切り。例 `5` / `10,5`） |
 * | `BOT_RUN_TIGHTEN_NEAR` | 『5 分前の強化』: `false`=しない / `true`=**修正後**（残り 5 分以下の評価で強化。`30,5` の 5 分前にも働く）/ `legacy`=旧の癖（最大のタイミングが 5 分以下のときだけ） |
 * | `BOT_RUN_PRIORITY_MODE` / `BOT_RUN_HIT_RATE_MIN_EV` / `BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE` | **任意**。未設定なら profile のまま（固定閾値方式）。`hit_rate` + `far=…,mid=…,near=…`（旧 `hit_rate_min_ev_schedule`。残り >=30 分 far、>=5 分 mid、それ以下 near） |
 * | `BOT_RUN_SNAPSHOT_HISTORY_LIMIT` | 急落判定に使う直近スナップショットの件数（2〜100。旧の既定は 3） |
 * | `BOT_RUN_RESULT_AFTER_MINUTES` / `BOT_RUN_RESULT_POLL_SECONDS` | 発走後の結果待ち（T3.9）。発走から結果の確認を始めるまで（1〜720 分。設計の案は 30）／確認の間隔（10〜3600 秒。案は 300）。締切は `CALENDAR_SETTLE_DEADLINE_MINUTES` |
 * | `BOT_RUN_ML_MODEL_TYPE` | `off`（ML 無効=市場確率）/ `legacy` / `calibration` / `alpha` / `ensemble`。パイプラインは legacy 固定（v2 は未承認） |
 * | `BOT_RUN_DECISION_PROFILE` | 判定の設定。**今は `legacy_function_defaults`（旧 `evaluate_odds` の関数既定値）だけ**。運用値ではない: 実 config の確認（T1.20）と承認までの明示的な選択 |
 * | `MODEL_STORE_URI` / `MODEL_VERIFY_PUBKEY` | ML を有効にするときだけ必須（CONTRACTS §5.1） |
 */
object BotRunSettingsParser {
    const val DECISION_PROFILE_LEGACY = "legacy_function_defaults"
    private const val MAX_RUNTIME_LIMIT_MINUTES = 840L

    fun fromEnv(
        env: Map<String, String>,
        appEnv: AppEnv,
    ): BotRunSettings {
        fun raw(name: String): String = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("$name を指定してください（運用の既定値はありません）")

        fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

        fun number(
            name: String,
            text: String,
        ): Double {
            val v = text.takeIf { it.matches(Regex("-?[0-9]{1,3}(\\.[0-9]{1,6})?")) }?.toDoubleOrNull()
            return checkNotNull(v) { "$name の数値の形式が不正です" }
        }

        fun long(
            name: String,
            min: Long,
            max: Long,
        ): Long {
            val s = raw(name)
            val n = s.takeIf { v -> v.all { it in '0'..'9' } && v.length <= 7 }?.toLongOrNull()
            check(n != null && n in min..max) { "$name は $min〜$max の整数を指定してください" }
            return n
        }

        fun seconds(
            name: String,
            min: Long,
            max: Long,
        ): Duration = Duration.ofSeconds(long(name, min, max))

        val lease = seconds("BOT_RUN_LEASE_SECONDS", 10, 3600)
        val heartbeat = seconds("BOT_RUN_HEARTBEAT_SECONDS", 1, 3600)
        check(heartbeat < lease) { "BOT_RUN_HEARTBEAT_SECONDS は BOT_RUN_LEASE_SECONDS より短くしてください" }

        val tighten =
            when (raw("BOT_RUN_TIGHTEN_NEAR")) {
                "false" -> TightenMode.OFF
                "true" -> TightenMode.CORRECTED
                "legacy" -> TightenMode.LEGACY_QUIRK
                else -> throw IllegalStateException("BOT_RUN_TIGHTEN_NEAR は false / true / legacy のいずれかを指定してください")
            }
        val timings =
            raw("BOT_RUN_SNAPSHOT_MINUTES").split(',').map { t ->
                val v = t.trim().takeIf { it.matches(Regex("[0-9]{1,4}(\\.[0-9]{1,2})?")) }?.toDoubleOrNull()
                check(v != null && v > 0.0 && v <= 1440.0) { "BOT_RUN_SNAPSHOT_MINUTES は 0 超 1440 以下の数をカンマ区切りで指定してください" }
                v
            }
        check(timings.toSet().size == timings.size) { "BOT_RUN_SNAPSHOT_MINUTES に重複があります" }

        val ml =
            when (raw("BOT_RUN_ML_MODEL_TYPE")) {
                "off" -> null
                "legacy" -> ProbabilityModelType.LEGACY
                "calibration" -> ProbabilityModelType.CALIBRATION
                "alpha" -> ProbabilityModelType.ALPHA
                "ensemble" -> ProbabilityModelType.ENSEMBLE
                else -> throw IllegalStateException("BOT_RUN_ML_MODEL_TYPE は off / legacy / calibration / alpha / ensemble のいずれかを指定してください")
            }
        var decision =
            when (raw("BOT_RUN_DECISION_PROFILE")) {
                DECISION_PROFILE_LEGACY -> WinDecisionConfig.legacyFunctionDefaults()
                else -> throw IllegalStateException("BOT_RUN_DECISION_PROFILE は $DECISION_PROFILE_LEGACY だけを指定できます（運用値は未確定）")
            }
        // 以下の 3 つは**任意**（未設定なら profile のまま = 現在の固定閾値方式）。設定したときだけ有効になる
        optional("BOT_RUN_PRIORITY_MODE")?.let {
            decision =
                decision.copy(
                    priorityMode =
                        if (it ==
                            "hit_rate"
                        ) {
                            PriorityMode.HIT_RATE
                        } else if (it ==
                            "edge"
                        ) {
                            PriorityMode.EDGE
                        } else {
                            throw IllegalStateException("BOT_RUN_PRIORITY_MODE は edge または hit_rate を指定してください")
                        },
                )
        }
        optional("BOT_RUN_HIT_RATE_MIN_EV")?.let { decision = decision.copy(hitRateMinEv = number("BOT_RUN_HIT_RATE_MIN_EV", it)) }
        val schedule =
            optional("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE")?.let { raw ->
                check(decision.priorityMode == PriorityMode.HIT_RATE) { "BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE は BOT_RUN_PRIORITY_MODE=hit_rate のときだけ使えます" }
                val parts = raw.split(',').associate { kv -> kv.trim().substringBefore('=').trim() to kv.substringAfter('=', "").trim() }
                check(parts.keys == setOf("far", "mid", "near")) { "BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE は far=…,mid=…,near=… の 3 つをすべて指定してください" }
                HitRateSchedule(
                    number("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE", parts.getValue("far")),
                    number("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE", parts.getValue("mid")),
                    number("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE", parts.getValue("near")),
                )
            }
        var storeUri: String? = null
        var pubKey: String? = null
        if (ml != null) {
            val uri = raw("MODEL_STORE_URI")
            check(uri.startsWith("gs://") || (appEnv == AppEnv.LOCAL && uri.startsWith("file:///"))) {
                "MODEL_STORE_URI は gs://（本番）または file:///（APP_ENV=local のみ）の形式にしてください"
            }
            storeUri = uri
            pubKey = raw("MODEL_VERIFY_PUBKEY")
        }

        val config =
            BotRunConfig(
                lease = LeaseSettings(lease, heartbeat),
                calendar = CalendarSettingsParser.fromEnv(env),
                polling =
                    PollingIntervals(
                        seconds("BOT_RUN_POLL_FAR_SECONDS", 1, 3600),
                        seconds("BOT_RUN_POLL_MID_SECONDS", 1, 3600),
                        seconds("BOT_RUN_POLL_NEAR_SECONDS", 1, 3600),
                    ),
                snapshotHistoryLimit = long("BOT_RUN_SNAPSHOT_HISTORY_LIMIT", 2, 100).toInt(),
                snapshot = SnapshotPolicy(timings, tighten),
                maxOddsAge = seconds("BOT_RUN_MAX_ODDS_AGE_SECONDS", 1, 86_400),
                maxRuntime = Duration.ofMinutes(long("BOT_RUN_MAX_RUNTIME_MINUTES", 1, MAX_RUNTIME_LIMIT_MINUTES)),
                staleClaimAfter = seconds("BOT_RUN_STALE_CLAIM_SECONDS", 60, 86_400),
                maxConsecutiveUnknown = MAX_CONSECUTIVE_UNKNOWN,
                modeCheckInterval = MODE_CHECK_INTERVAL,
                maxConsecutiveCycleFailures = long("BOT_RUN_MAX_CYCLE_FAILURES", 1, 20).toInt(),
                mlModelType = ml,
                decision = decision,
                hitRateSchedule = schedule,
                resultWait =
                    ResultWaitConfig(
                        Duration.ofMinutes(long("BOT_RUN_RESULT_AFTER_MINUTES", 1, 720)),
                        seconds("BOT_RUN_RESULT_POLL_SECONDS", 10, 3600),
                    ),
            )
        return BotRunSettings(config, storeUri, pubKey)
    }

    /** CONTRACTS §5.3: UNKNOWN が 3 回連続（約 45 秒）で DRAINING。契約の値なので環境変数にしない。 */
    const val MAX_CONSECUTIVE_UNKNOWN = 3

    /** CONTRACTS §5.3: 運用モードの確認はキャッシュ ≤ 15 秒の間隔。heartbeat の間隔より長くならないよう、heartbeat が短いときはそれに合わせる。 */
    val MODE_CHECK_INTERVAL: Duration = Duration.ofSeconds(15)
}
