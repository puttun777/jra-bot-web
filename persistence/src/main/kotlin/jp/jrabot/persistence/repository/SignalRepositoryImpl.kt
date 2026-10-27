package jp.jrabot.persistence.repository

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.RecordedSignal
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.value.Probability
import jp.jrabot.persistence.dao.SignalDao
import jp.jrabot.persistence.dao.SignalEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toInstantUtc
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant

internal class SignalRepositoryImpl(
    private val dao: SignalDao,
    private val clock: Clock,
    private val tx: TransactionOperations,
) : SignalRepository {
    override fun find(
        tenantId: TenantId,
        id: SignalId,
    ): Signal? = dao.selectById(tenantId.value, id.value)?.toDomain()

    override fun listByRace(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<Signal> = dao.selectByRace(tenantId.value, raceId.value).map { it.toDomain() }

    override fun listUnsent(
        tenantId: TenantId,
        limit: Int,
    ): List<Signal> {
        require(limit in 1..MAX_UNSENT) { "limit は 1〜$MAX_UNSENT: $limit" }
        return dao.selectUnsent(tenantId.value, limit).map { it.toDomain() }
    }

    override fun listClaimedBefore(
        tenantId: TenantId,
        cutoff: Instant,
        limit: Int,
    ): List<Signal> {
        require(limit in 1..MAX_UNSENT) { "limit は 1〜$MAX_UNSENT: $limit" }
        return dao.selectClaimedBefore(tenantId.value, cutoff.toDbTime(), limit).map { it.toDomain() }
    }

    /**
     * 自然キー `(tenant_id, race_id, market, umaban, observed_at)` の UNIQUE（1062・`uq_signals_natural`）で重複と判定し、
     * 既存の行（通知の状態を含め変更しない）を返す。他のエラー（FK・CHECK など）はそのまま投げる。新規の状態は UNSENT だけ。
     */
    override fun insertIfAbsent(signal: Signal): RecordedSignal {
        require(signal.state == NotificationState.UNSENT) { "新規の Signal の状態は UNSENT: ${signal.state}" }
        val entity = signal.toEntity()
        return tx.inTransaction {
            val outcome =
                try {
                    dao.insert(entity)
                    InsertOutcome.INSERTED
                } catch (e: RuntimeException) {
                    if (e.isDuplicateOn("signals", NATURAL_KEY)) InsertOutcome.ALREADY_EXISTS else throw e
                }
            val stored =
                checkNotNull(dao.selectByNaturalKey(entity.tenantId, entity.raceId, entity.market, entity.umaban, entity.observedAt)) {
                    "Signal の自然キーが重複と判定されたが行が読めない。呼び出し側のトランザクションが長すぎないか確認する"
                }
            RecordedSignal(outcome, stored.toDomain())
        }
    }

    /** `UPDATE … WHERE notify_state = 'UNSENT'` の更新件数が 1 のときだけ勝ち（同時に呼んでも勝者は 1 つ）。 */
    override fun claim(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean = dao.claim(transitionEntity(tenantId, id)).count == 1

    override fun markSent(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean = tx.inTransaction { transition(tenantId, id, NotificationState.SENT) { dao.markSent(it).count } }

    override fun markSendUnknown(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean = tx.inTransaction { transition(tenantId, id, NotificationState.SEND_UNKNOWN) { dao.markSendUnknown(it).count } }

    /** CLAIMED → [target]。0 件なら現在の状態を見て、既に [target] なら true（冪等）、それ以外（不在・UNSENT・別の終端）は false。 */
    private fun transition(
        tenantId: TenantId,
        id: SignalId,
        target: NotificationState,
        update: (SignalEntity) -> Int,
    ): Boolean {
        if (update(transitionEntity(tenantId, id)) == 1) return true
        return dao.selectById(tenantId.value, id.value)?.notifyState == target.name
    }

    private fun transitionEntity(
        tenantId: TenantId,
        id: SignalId,
    ): SignalEntity {
        val now = clock.now().toDbTime()
        return SignalEntity(
            id = id.value,
            tenantId = tenantId.value,
            raceId = "",
            market = "",
            umaban = 0,
            observedAt = now,
            odds = 0.0,
            prob = 0.0,
            ev = 0.0,
            isPaper = false,
            stake = 0.0,
            probSource = null,
            notifyState = "",
            notifyUpdatedAt = now,
            createdAt = now,
        )
    }

    private fun Signal.toEntity(): SignalEntity {
        val now = clock.now().toDbTime()
        return SignalEntity(
            id = 0,
            tenantId = tenantId.value,
            raceId = raceId.value,
            market = market.code,
            umaban = umaban.value,
            observedAt = observedAt.toDbTime(),
            odds = odds.value,
            prob = prob.value,
            ev = ev,
            isPaper = isPaper,
            stake = stake,
            probSource = probSource,
            notifyState = state.name,
            notifyUpdatedAt = null,
            createdAt = now,
        )
    }

    private companion object {
        const val NATURAL_KEY = "uq_signals_natural"
        const val MAX_UNSENT = 1000
    }
}

internal fun SignalEntity.toDomain(): Signal =
    Signal(
        tenantId = TenantId.of(tenantId),
        id = SignalId.of(id),
        raceId = RaceId.parse(raceId),
        market = Market.fromCodeOrNull(market) ?: throw IllegalStateException("signals.market '$market' がドメインに無い"),
        umaban = HorseNumber.of(umaban),
        observedAt = observedAt.toInstantUtc(),
        odds = Odds.of(odds),
        prob = Probability.of(prob),
        ev = ev,
        isPaper = isPaper,
        stake = stake,
        probSource = probSource,
        state = enumFromDb<NotificationState>(notifyState, "signals.notify_state"),
    )
