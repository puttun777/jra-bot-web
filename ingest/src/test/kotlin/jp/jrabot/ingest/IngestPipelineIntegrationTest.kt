package jp.jrabot.ingest

import jp.jrabot.domain.calendar.DayVerdict
import jp.jrabot.domain.ingest.BackfillRequestResult
import jp.jrabot.domain.ingest.BackfillStatus
import jp.jrabot.domain.ingest.BackfillStore
import jp.jrabot.domain.ingest.FetchStatus
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.integration.runConcurrently
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 取得の事実・カレンダー反映・オッズ・カーソル・バックフィル・テナント境界（本物の MySQL。合成の正規化レコードだけ。実データなし）。 */
@Tag("integration")
class IngestPipelineIntegrationTest {
    private val it = IngestIt
    private val race = "202611010511" // 2026-11-01 東京 11R
    private val t1 = TenantId.of(1)

    @BeforeEach
    fun setUp() = it.reset()

    private fun yschBatch(
        fid: String,
        seq: Int,
        eof: Boolean,
        vararg records: Map<String, Any?>,
        from: String? = "20261101",
        to: String? = "20271231",
    ) = it.envelope(dataspec = "YSCH", eof = eof, fetch = it.fetch(fid, seq, from, to), records = records.toList(), option = null, fromtime = "20261001000000")

    // ---------------- 取得の事実（YSCH） ----------------

    @Test
    fun `YSCH は複数バッチの途中では取得成功にならず、EOFまで連続して読んだときだけ事実になる`() {
        val fid = it.fetchId()
        val d1 = LocalDate.of(2026, 11, 1)
        val a = it.parse(it.ingest(it.one, yschBatch(fid, 0, false, it.nys("20261101"), it.nys("20261101", venue = "09", kai = 2))))
        assertEquals("IN_PROGRESS", (a["fetch"] as Map<*, *>)["status"])
        assertNull(it.facts.latestSuccess(t1, d1), "途中では事実にしない")
        assertNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 11, 3)))
        // 行のある日は開催日として反映され、行の無い日は「不明」のまま
        assertEquals(2, it.count("SELECT COUNT(*) FROM race_day WHERE race_date = '2026-11-01'"))
        assertEquals(DayVerdict.UNKNOWN, it.effectiveDay(1, LocalDate.of(2026, 11, 3)).verdict)

        val b = it.parse(it.ingest(it.one, yschBatch(fid, 1, true, it.nys("20261122", venue = "08", kai = 5, nichime = 9))))
        assertEquals("SUCCEEDED", (b["fetch"] as Map<*, *>)["status"])
        assertEquals(FetchStatus.SUCCEEDED, it.fetchStatus(fid))
        assertEquals("2026-11-01", it.db.scalar("SELECT covered_from FROM ingest_fetch WHERE fetch_id = '$fid'"))
        assertEquals("2026-11-22", it.db.scalar("SELECT covered_to FROM ingest_fetch WHERE fetch_id = '$fid'"), "申告(2027-12-31)ではなく、見えた最後の開催日まで")
        val fact = assertNotNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 11, 3)))
        assertEquals(LocalDate.of(2026, 11, 3), fact.date)
        assertNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 11, 23)), "最後の開催日より先は『まだ公表されていない』と区別できないので事実にしない")
        assertNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 10, 31)), "対象範囲より前も事実にしない")
        assertNull(it.facts.latestSuccess(TenantId.of(2), LocalDate.of(2026, 11, 3)), "別テナントには事実が無い")
        // 取得成功・予定なし → 開催なしの確認。取得していない日は不明。取得成功・予定あり（行のある日）は開催日の行が確認済み
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, it.effectiveDay(1, LocalDate.of(2026, 11, 3)).verdict)
        assertEquals(DayVerdict.UNKNOWN, it.effectiveDay(1, LocalDate.of(2026, 11, 23)).verdict)
        assertEquals(DayVerdict.UNKNOWN, it.effectiveDay(2, LocalDate.of(2026, 11, 3)).verdict)
        assertEquals(3, it.count("SELECT COUNT(*) FROM race_day WHERE last_confirmed_at IS NOT NULL"))
    }

    @Test
    fun `取得の事実が古くなったら開催なしと確認したことにしない（STALE）`() {
        val fid = it.fetchId()
        it.ingest(it.one, yschBatch(fid, 0, true, it.nys("20261122", venue = "08", kai = 5, nichime = 9)))
        assertEquals(DayVerdict.NO_RACE_CONFIRMED, it.effectiveDay(1, LocalDate.of(2026, 11, 3)).verdict)
        ItClock.fixed =
            java.time.Instant
                .now()
                .plus(java.time.Duration.ofHours(121))
        assertEquals(DayVerdict.STALE, it.effectiveDay(1, LocalDate.of(2026, 11, 3)).verdict)
    }

    @Test
    fun `開催日を1件も含まない成功は事実にならない（予定なしを確認したことにしない）`() {
        val fid = it.fetchId()
        val r = it.parse(it.ingest(it.one, yschBatch(fid, 0, true)))
        assertEquals("SUCCEEDED", (r["fetch"] as Map<*, *>)["status"])
        assertNull(it.db.scalar("SELECT covered_to FROM ingest_fetch WHERE fetch_id = '$fid'"))
        assertNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 11, 3)))
        assertEquals(DayVerdict.UNKNOWN, it.effectiveDay(1, LocalDate.of(2026, 11, 3)).verdict)
    }

    @Test
    fun `YSCH を取得していない・取得に失敗した・実データのレコードで解析できない場合は事実にならない`() {
        // 取得していない
        assertEquals(DayVerdict.UNKNOWN, it.effectiveDay(1, LocalDate.of(2026, 11, 3)).verdict)
        // 実データ形式（JV-Data の YS）は仕様未確認で解析できない → 保留（PENDING）、取得は失敗、事実なし
        val fid = it.fetchId()
        val r = it.parse(it.ingest(it.one, yschBatch(fid, 0, true, it.rec("YS", "20261101050301".toByteArray()))))
        assertEquals("FAILED", (r["fetch"] as Map<*, *>)["status"])
        assertEquals(1, r["unsupported"])
        assertEquals("PENDING", it.db.scalar("SELECT parse_status FROM ingest_raw"), "解析成功を偽装しない")
        assertEquals("NO_PARSER:YSCH/YS", it.db.scalar("SELECT parse_error FROM ingest_raw"))
        assertEquals("UNPARSED_RECORDS", it.db.scalar("SELECT fail_reason FROM ingest_fetch WHERE fetch_id = '$fid'"))
        assertNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 11, 3)))
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day"))
        // 取得失敗が履歴(FETCH_FAILURE)と取りこぼし区間に残る
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'FETCH_FAILURE' AND source = 'YSCH'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_gap WHERE reason = 'FETCH_FAILURE' AND resolved_at IS NULL"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_cursor"), "失敗した取得ではカーソルを進めない")
    }

    @Test
    fun `不正なレコードは隔離され、取得は失敗し、有効なレコードは適用される`() {
        val fid = it.fetchId()
        val bad = it.norm("NYS", mapOf("date" to "20261301", "venue" to "05", "kai" to 1, "nichime" to 1, "status" to "PLANNED", "replacedBy" to null))
        val r = it.parse(it.ingest(it.one, yschBatch(fid, 0, true, it.nys("20261101"), bad)))
        assertEquals(1, r["quarantined"])
        assertEquals(2, r["accepted"], "不正でも生レコードは保存して ACK する（再送させない）")
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'FAILED'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'PARSED'"))
        assertEquals("PARSE_FAILURES", it.db.scalar("SELECT fail_reason FROM ingest_fetch WHERE fetch_id = '$fid'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_day"), "有効なレコードの適用は失われない")
        assertNull(it.facts.latestSuccess(t1, LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun `取得セッションの連番： 先走りは409（手前を待つ）、重複は422、終了後は422`() {
        val fid = it.fetchId()
        val ahead = it.ingest(it.one, yschBatch(fid, 1, true, it.nys("20261101")))
        assertEquals(409, ahead.status)
        assertTrue(ahead.body.contains("FETCH_SEQ_AHEAD"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day"), "手前が来る前に反映しない")
        assertEquals(200, it.ingest(it.one, yschBatch(fid, 0, false, it.nys("20261101"))).status)
        assertEquals(422, it.ingest(it.one, yschBatch(fid, 0, false, it.nys("20261108"))).status.also { s -> assertEquals(422, s) }, "同じ連番を別のバッチが使う")
        assertTrue(it.ingest(it.one, yschBatch(fid, 0, false, it.nys("20261115"))).body.contains("FETCH_SEQ_REUSED"))
        assertEquals(200, it.ingest(it.one, yschBatch(fid, 1, true, it.nys("20261108"))).status)
        assertEquals(FetchStatus.SUCCEEDED, it.fetchStatus(fid))
        assertEquals(422, it.ingest(it.one, yschBatch(fid, 2, true, it.nys("20261115"))).status, "終了した取得セッションには足せない")
        // 属性が食い違う取得セッション（別の範囲）は拒否
        val fid2 = it.fetchId()
        assertEquals(200, it.ingest(it.one, yschBatch(fid2, 0, false, it.nys("20261101"))).status)
        assertEquals(422, it.ingest(it.one, yschBatch(fid2, 1, true, it.nys("20261108"), to = "20281231")).status)
    }

    // ---------------- カレンダー ----------------

    @Test
    fun `RACE の版の優先順位： 古い版は反映せず、中止は復活しない（source_version）`() {
        fun send(
            kubun: String,
            created: String,
            post: String? = "1525",
        ): Map<String, Any?> = it.parse(it.ingest(it.one, it.envelope(records = listOf(it.nra(race, kubun, created, post)))))
        send("2", "20261030")
        assertEquals("202610302", it.db.scalar("SELECT source_version FROM race_schedule WHERE race_id = '$race'"))
        assertEquals("CARD", it.db.scalar("SELECT status FROM race_schedule WHERE race_id = '$race'"))
        send("1", "20261029") // 古い版（作成日が前）
        assertEquals("202610302", it.db.scalar("SELECT source_version FROM race_schedule WHERE race_id = '$race'"), "古い版で新しい予定を上書きしない")
        send("2", "20261031", "1540") // 新しい版で時刻変更
        assertEquals("15:40:00", it.db.scalar("SELECT TIME(CONVERT_TZ(scheduled_post_at, '+00:00', '+09:00')) FROM race_schedule WHERE race_id = '$race'"))
        send("9", "20261031") // 同日の中止
        assertEquals("CANCELLED", it.db.scalar("SELECT status FROM race_schedule WHERE race_id = '$race'"))
        send("2", "20261101", "1600") // 中止の後に新しい出馬表が来ても復活させない
        assertEquals("CANCELLED", it.db.scalar("SELECT status FROM race_schedule WHERE race_id = '$race'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM races WHERE race_id = '$race'"), "races の最小行ができる（オッズの参照先）")
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'CANCELLED'"))
    }

    @Test
    fun `RA は開催日を CONFIRMED にし、YSCH の再取得では状態を戻さず確認の時刻だけを進める（確認済みの日が古くならない）`() {
        // YSCH で PLANNED の開催日ができ、RA（出馬表）が届くと CONFIRMED になる
        assertEquals(200, it.ingest(it.one, yschBatch(it.fetchId(), 0, true, it.nys("20261101"))).status)
        assertEquals("PLANNED", it.db.scalar("SELECT status FROM race_day"))
        assertEquals(200, it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030")))).status)
        assertEquals("CONFIRMED", it.db.scalar("SELECT status FROM race_day"))
        val first = it.db.scalar("SELECT last_confirmed_at FROM race_day")
        assertEquals(DayVerdict.CONFIRMED, it.effectiveDay(1, LocalDate.of(2026, 11, 1)).verdict)
        // 次の YSCH が同じ日を PLANNED として返しても、CONFIRMED のまま。確認の時刻は進む
        ItClock.fixed =
            java.time.Instant
                .now()
                .plusSeconds(3600)
        assertEquals(200, it.ingest(it.one, yschBatch(it.fetchId(), 0, true, it.nys("20261101"))).status)
        assertEquals("CONFIRMED", it.db.scalar("SELECT status FROM race_day"))
        assertTrue(it.db.scalar("SELECT last_confirmed_at FROM race_day")!! > first!!, "YSCH の再確認で確認の時刻が進む")
        assertEquals(
            2,
            it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'DAY_STATUS' AND race_date = '2026-11-01'"),
            "PLANNED の作成と CONFIRMED への遷移の 2 件",
        )
        // 中止された開催日は YSCH の PLANNED で復活しない（初期状態に CANCELLED は使えないので、PLANNED→CANCELLED→PLANNED）
        for (status in listOf("PLANNED", "CANCELLED", "PLANNED")) {
            assertEquals(200, it.ingest(it.one, yschBatch(it.fetchId(), 0, true, it.nys("20261108", venue = "09", kai = 2, status = status))).status)
        }
        assertEquals("CANCELLED", it.db.scalar("SELECT status FROM race_day WHERE venue_code = '09'"))
    }

    @Test
    fun `0B14 の発走時刻変更とその取消が予定に反映される`() {
        it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030"))))

        fun tc(
            at: String,
            revoked: Boolean,
            key: String,
        ) = it.envelope(
            dataspec = "0B14",
            cls = "R",
            eof = false,
            option = null,
            fromtime = null,
            lastFileTimestamp = null,
            records = listOf(it.norm("NTC", mapOf("raceId" to race, "postAt" to at, "revoked" to revoked, "snapshotKey" to key))),
        )
        assertEquals(200, it.ingest(it.one, tc("2026-11-01T06:45:00Z", false, "snap1")).status)
        assertEquals("2026-11-01 06:45:00", it.db.scalar("SELECT scheduled_post_at FROM race_schedule WHERE race_id = '$race'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'TIME_CHANGE' AND source = '0B14'"))
        assertEquals(200, it.ingest(it.one, tc("2026-11-01T06:25:00Z", true, "snap2")).status)
        assertEquals("2026-11-01 06:25:00", it.db.scalar("SELECT scheduled_post_at FROM race_schedule WHERE race_id = '$race'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'REVOKED'"))
    }

    // ---------------- オッズ ----------------

    private fun odds(
        vararg r: Map<String, Any?>,
        at: java.time.Instant = ItClock.now(),
    ) = it.envelope(cls = "R", dataspec = "0B31", eof = false, option = null, fromtime = null, lastFileTimestamp = null, records = r.toList(), observedAt = at)

    @Test
    fun `単勝オッズの保存： upsert・履歴・取得時刻・1倍未満の隔離・レース未登録の保留`() {
        it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030"))))
        val at = ItClock.now().minusSeconds(30)
        assertEquals(200, it.ingest(it.one, odds(it.now(race, 1 to 2.5, 2 to 10.0), at = at)).status)
        assertEquals(2, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE market = 'win'"))
        // 同じ観測の再送は増えない。値が違えば upsert で更新（同じ時刻の自然キー）
        it.ingest(it.one, odds(it.now(race, 1 to 2.5, 2 to 10.0), at = at))
        assertEquals(2, it.count("SELECT COUNT(*) FROM odds_snapshots"))
        it.ingest(it.one, odds(it.now(race, 1 to 2.6, 2 to 10.0), at = at))
        assertEquals("2.6", it.db.scalar("SELECT odds FROM odds_snapshots WHERE umaban = 1"))
        // 別の時刻は履歴として増える
        it.ingest(it.one, odds(it.now(race, 1 to 2.4, 2 to 9.5), at = ItClock.now()))
        assertEquals(4, it.count("SELECT COUNT(*) FROM odds_snapshots"))
        assertEquals(2, it.count("SELECT COUNT(DISTINCT observed_at) FROM odds_snapshots"))
        // 1.0 倍は有効（元本が戻るだけ）。0 < odds < 1 は取込の境界で隔離（旧 Python の数値は変えない: 計算側の Odds は変更なし）
        val par = it.parse(it.ingest(it.one, odds(it.now(race, 3 to 1.0), at = ItClock.now().plusSeconds(1))))
        assertEquals(0, par["quarantined"])
        val below = it.parse(it.ingest(it.one, odds(it.now(race, 4 to 0.5), at = ItClock.now().plusSeconds(2))))
        assertEquals(1, below["quarantined"])
        assertEquals(0, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE umaban = 4"))
        assertEquals("FAILED", it.db.scalar("SELECT parse_status FROM ingest_raw WHERE parse_error = 'ODDS_BELOW_PAR'"))
        // レース未登録のオッズは保存せず、保留（PENDING）
        val unknown = it.parse(it.ingest(it.one, odds(it.now("202611020511", 1 to 3.0), at = ItClock.now().plusSeconds(3))))
        assertEquals(1, unknown["unsupported"])
        assertEquals("PENDING", it.db.scalar("SELECT parse_status FROM ingest_raw WHERE parse_error = 'RACE_NOT_FOUND'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE race_id = '202611020511'"))
    }

    @Test
    fun `レース待ちで保留にしたオッズは レースが届いた時点で 取り込んだときの観測時刻のまま再適用される（監査 G2）`() {
        val at = ItClock.now().minusSeconds(30)
        val ack = it.parse(it.ingest(it.one, odds(it.now(race, 1 to 2.5, 2 to 10.0), at = at)))
        assertEquals(1, ack["unsupported"])
        assertEquals(0, it.count("SELECT COUNT(*) FROM odds_snapshots"))
        assertEquals("PENDING", it.db.scalar("SELECT parse_status FROM ingest_raw WHERE parse_error = 'RACE_NOT_FOUND'"))
        // レースの情報が後から届く（別のバッチ）
        assertEquals(200, it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030")))).status)
        assertEquals(2, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE market = 'win'"), "保留にしていたオッズが保存された")
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'PENDING'"), "保留のまま残さない")
        assertEquals("PARSED", it.db.scalar("SELECT parse_status FROM ingest_raw WHERE dataspec = '0B31'"))
        // 観測時刻は取り込んだバッチの時刻（再適用した時刻ではない）
        assertEquals(2, it.count("SELECT COUNT(*) FROM odds_snapshots o JOIN ingest_batch b ON b.dataspec = '0B31' AND b.observed_at = o.observed_at"))
        // もう一度レースが届いても重複しない（冪等）
        it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030", name = "改名"))))
        assertEquals(2, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE market = 'win'"))
    }

    @Test
    fun `同じバッチの中でオッズがレースより前に並んでいても 保留にしない 依存するレースを先に適用する（監査 G2）`() {
        val ack = it.parse(it.ingest(it.one, it.envelope(records = listOf(it.now(race, 1 to 2.5), it.nra(race, "2", "20261030")))))
        assertEquals(0, ack["unsupported"])
        assertEquals(1, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE market = 'win'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'PENDING'"))
        // 生レコードは元の並び（seq）のまま保存する
        assertEquals("NOW", it.db.scalar("SELECT record_id FROM ingest_raw ORDER BY seq LIMIT 1"))
    }

    @Test
    fun `レース待ちの保留が別のテナントに影響しない（テナント境界）`() {
        val at = ItClock.now().minusSeconds(30)
        it.ingest(it.one, odds(it.now(race, 1 to 2.5), at = at))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'PENDING'"))
        // 別テナントの同じレースIDのレース情報が届いても、テナント1の保留は再適用されない
        it.ingest(it.two, it.envelope(agent = it.two, records = listOf(it.nra(race, "2", "20261030"))))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 1 AND parse_status = 'PENDING'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM odds_snapshots WHERE tenant_id = 1"))
    }

    @Test
    fun `古い速報オッズ（保持期間を過ぎた観測）は封筒の段階で拒否する`() {
        it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030"))))
        val r = it.ingest(it.one, odds(it.now(race, 1 to 2.5), at = ItClock.now().minus(java.time.Duration.ofDays(8))))
        assertEquals(422, r.status)
        assertTrue(r.body.contains("OBSERVED_AT_TOO_OLD"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM odds_snapshots"))
    }

    @Test
    fun `ワイド・三連複： 組み合わせキーを昇順に正規化して保存し、矛盾するキーは隔離する`() {
        it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030"))))
        val wide =
            it.norm(
                "NOD",
                mapOf(
                    "raceId" to race,
                    "entries" to
                        listOf(
                            mapOf("a" to 5, "b" to 2, "low" to 3.1, "high" to 4.2, "pop" to 3),
                            mapOf(
                                "a" to 12,
                                "b" to 1,
                                "low" to 20.0,
                                "high" to null,
                                "pop" to null,
                            ),
                        ),
                ),
            )
        val san = it.norm("NOS", mapOf("raceId" to race, "entries" to listOf(mapOf("a" to 9, "b" to 1, "c" to 4, "odds" to 55.5, "pop" to 7))))
        val at = ItClock.now().minusSeconds(10)
        val r1 =
            it.ingest(
                it.one,
                it.envelope(
                    cls = "R",
                    dataspec = "0B33",
                    eof = false,
                    option = null,
                    fromtime = null,
                    lastFileTimestamp = null,
                    records = listOf(wide, san),
                    observedAt = at,
                ),
            )
        assertEquals(200, r1.status, r1.body)
        assertEquals("02-05", it.db.scalar("SELECT combo_key FROM combo_odds_snapshots WHERE market = 'wide' AND odds = 3.1"))
        assertEquals("4.2", it.db.scalar("SELECT odds_high FROM combo_odds_snapshots WHERE combo_key = '02-05'"))
        assertEquals("01-12", it.db.scalar("SELECT combo_key FROM combo_odds_snapshots WHERE odds = 20"))
        assertEquals("01-04-09", it.db.scalar("SELECT combo_key FROM combo_odds_snapshots WHERE market = 'sanrenpuku'"))
        // 再送で増えない
        it.ingest(
            it.one,
            it.envelope(
                cls = "R",
                dataspec = "0B33",
                eof = false,
                option = null,
                fromtime = null,
                lastFileTimestamp = null,
                records = listOf(wide, san),
                observedAt = at,
            ),
        )
        assertEquals(3, it.count("SELECT COUNT(*) FROM combo_odds_snapshots"))
        // (2,5) と (5,2) が違う値で両方ある → 矛盾として隔離
        val conflict =
            it.norm(
                "NOD",
                mapOf(
                    "raceId" to race,
                    "entries" to
                        listOf(
                            mapOf("a" to 2, "b" to 5, "low" to 3.1, "high" to null, "pop" to null),
                            mapOf(
                                "a" to 5,
                                "b" to 2,
                                "low" to 9.0,
                                "high" to null,
                                "pop" to null,
                            ),
                        ),
                ),
            )
        val c =
            it.parse(
                it.ingest(
                    it.one,
                    it.envelope(
                        cls = "R",
                        dataspec = "0B33",
                        eof = false,
                        option = null,
                        fromtime = null,
                        lastFileTimestamp = null,
                        records = listOf(conflict),
                        observedAt = ItClock.now(),
                    ),
                ),
            )
        assertEquals(1, c["quarantined"])
        assertEquals("DUPLICATE_COMBO_KEY", it.db.scalar("SELECT parse_error FROM ingest_raw WHERE parse_status = 'FAILED'"))
        // 同じ組が同じ値で重複 → 1 行に吸収
        val dup =
            it.norm(
                "NOS",
                mapOf(
                    "raceId" to race,
                    "entries" to
                        listOf(
                            mapOf("a" to 1, "b" to 2, "c" to 3, "odds" to 8.0, "pop" to null),
                            mapOf(
                                "a" to 3,
                                "b" to 1,
                                "c" to 2,
                                "odds" to 8.0,
                                "pop" to null,
                            ),
                        ),
                ),
            )
        assertEquals(
            200,
            it
                .ingest(
                    it.one,
                    it.envelope(
                        cls = "R",
                        dataspec = "0B35",
                        eof = false,
                        option = null,
                        fromtime = null,
                        lastFileTimestamp = null,
                        records = listOf(dup),
                        observedAt = ItClock.now().plusSeconds(1),
                    ),
                ).status,
        )
        assertEquals(1, it.count("SELECT COUNT(*) FROM combo_odds_snapshots WHERE combo_key = '01-02-03'"))
    }

    @Test
    fun `実データ形式のレコード（JV-Data の ID）は解析成功にならず、保留のまま何も適用しない`() {
        val r = it.parse(it.ingest(it.one, it.envelope(records = listOf(it.rec("RA", ByteArray(100) { 65 }), it.rec("SE", ByteArray(50) { 66 })))))
        assertEquals(2, r["unsupported"])
        assertEquals(2, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'PENDING' AND parse_error LIKE 'NO_PARSER:RACE/%'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_raw WHERE parse_status = 'PARSED'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_schedule") + it.count("SELECT COUNT(*) FROM races"))
    }

    // ---------------- テナント境界 ----------------

    @Test
    fun `別テナントのレースへのオッズは適用されず、別テナントの取得の事実は見えない`() {
        it.ingest(it.one, it.envelope(records = listOf(it.nra(race, "2", "20261030"))))
        // ag_two（テナント 2）が、テナント 1 のレースに対するオッズを送る → テナント 2 にはそのレースが無いので保留
        val r = it.parse(it.ingest(it.two, odds(it.now(race, 1 to 2.5)).toMutableMap().also { m -> m["agentId"] = "ag_two" }))
        assertEquals(1, r["unsupported"])
        assertEquals(0, it.count("SELECT COUNT(*) FROM odds_snapshots"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 2 AND parse_status = 'PENDING'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 1 AND dataspec = '0B31'"))
        // 取得セッションの ID を別テナントのエージェントが使っても、自分のテナントの取得セッションになる（テナント 1 のものは見えない・触れない）
        val fid = it.fetchId()
        it.ingest(it.one, yschBatch(fid, 0, true, it.nys("20261101")))
        val clash = it.ingest(it.two, yschBatch(fid, 0, true, it.nys("20261101")).toMutableMap().also { m -> m["agentId"] = "ag_two" })
        assertEquals(200, clash.status, clash.body)
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_fetch WHERE tenant_id = 2 AND fetch_id = '$fid'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_fetch WHERE tenant_id = 1 AND fetch_id = '$fid'"))
    }

    // ---------------- ACK・カーソル・再開 ----------------

    private fun raceFetch(
        fid: String,
        seq: Int,
        eof: Boolean,
        lastTs: String?,
        backfillId: Long? = null,
        vararg records: Map<String, Any?>,
    ) = it.envelope(
        dataspec = "RACE",
        eof = eof,
        fetch = it.fetch(fid, seq, null, null, backfillId),
        records =
            records.toList().ifEmpty {
                listOf(it.rec("RA", "x$seq$fid".toByteArray()))
            },
        lastFileTimestamp = lastTs,
    )

    @Test
    fun `カーソルはEOFまで連続して読了した取得だけで前進し、後戻りしない`() {
        val f1 = it.fetchId()
        val a = it.parse(it.ingest(it.one, raceFetch(f1, 0, false, "20261031200000")))
        assertNull(a["cursor"], "途中のバッチではカーソルを進めない")
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_cursor"))
        val b = it.parse(it.ingest(it.one, raceFetch(f1, 1, true, "20261031200000")))
        assertEquals(mapOf("dataType" to "RACE:2", "lastFileTimestamp" to "20261031200000"), b["cursor"])
        assertEquals("20261031200000", it.db.scalar("SELECT last_file_timestamp FROM ingest_cursor WHERE data_type = 'RACE:2'"))
        // 古い timestamp の取得が後から完了しても後戻りしない
        val f2 = it.fetchId()
        val c = it.parse(it.ingest(it.one, raceFetch(f2, 0, true, "20261001000000")))
        // D-144: サーバのカーソルがこの取得の値以上なので『確認できた』として値を返す（エージェントの acked_ts も前進だけ）。サーバのカーソルは動かさない
        assertEquals(mapOf("dataType" to "RACE:2", "lastFileTimestamp" to "20261001000000"), c["cursor"])
        assertEquals("20261031200000", it.db.scalar("SELECT last_file_timestamp FROM ingest_cursor WHERE data_type = 'RACE:2'"))
        // 新しければ前進
        val f3 = it.fetchId()
        assertNotNull(it.parse(it.ingest(it.one, raceFetch(f3, 0, true, "20261101120000")))["cursor"])
        assertEquals("20261101120000", it.db.scalar("SELECT last_file_timestamp FROM ingest_cursor WHERE data_type = 'RACE:2'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_cursor WHERE tenant_id = 2"))
    }

    // ---------------- バックフィル ----------------

    /** バックフィル要求の作成は bot / 管理操作の権限（ingest ユーザーは作れない）なので、テストでは管理者の SQL で作る。重複排除の検証は :persistence の統合テスト。 */
    private fun requestBackfill(
        dataspec: String,
        option: Int?,
        fromtime: String,
        gapId: Long? = null,
    ): Long {
        it.db.exec(
            "INSERT INTO ingest_backfill (tenant_id, dataspec, option_no, fromtime, gap_id, requested_at, requested_by, updated_at) " +
                "VALUES (1, '$dataspec', ${option ?: "NULL"}, '$fromtime', ${gapId ?: "NULL"}, NOW(6), 'test', NOW(6))",
        )
        return it.db.scalar("SELECT MAX(id) FROM ingest_backfill")!!.toLong()
    }

    @Test
    fun `バックフィル： 要求の重複登録なし・再送受理・取りこぼし区間の解消・現在のカーソルを動かさない・失敗したら再開可能`() {
        // 取りこぼし区間（エージェント不達）を 1 つ作る
        it.db.exec(
            "INSERT INTO ingest_gap (tenant_id, agent_id, dataspec, reason, gap_from, detected_at) VALUES (1, 'ag_one', 'RACE', 'AGENT_UNREACHABLE', '2026-10-01 00:00:00', NOW(6))",
        )
        val gapId = it.db.scalar("SELECT id FROM ingest_gap")!!.toLong()
        val created = requestBackfill("RACE", 2, "20261001000000", gapId).let { id -> BackfillRequestResult.Created(id) }

        // 存在しない要求・種別が違う要求は拒否
        assertEquals(422, it.ingest(it.one, raceFetch(it.fetchId(), 0, true, "20261101000000", backfillId = 9999)).status)
        val wrong =
            it.envelope(
                dataspec = "YSCH",
                option = null,
                fetch = it.fetch(it.fetchId(), 0, "20261101", "20271231", created.id),
                records = listOf(it.nys("20261101")),
            )
        assertEquals(422, it.ingest(it.one, wrong).status)

        // 1 回目の取得は失敗（保留レコードのある YSCH ではなく、ここでは途中で別 fetch に乗っ取られる前提を使わず RACE の完了を確認）
        val fid = it.fetchId()
        val first = it.parse(it.ingest(it.one, raceFetch(fid, 0, false, "20261101000000", created.id)))
        assertEquals("IN_PROGRESS", (first["fetch"] as Map<*, *>)["status"])
        assertEquals("IN_PROGRESS", it.db.scalar("SELECT status FROM ingest_backfill WHERE id = ${created.id}"))
        assertEquals(fid, it.db.scalar("SELECT fetch_id FROM ingest_backfill WHERE id = ${created.id}"))
        // 別の取得セッションが同じバックフィルを横取りしない（進行中。引き継ぎ時間内）
        val other = it.ingest(it.one, raceFetch(it.fetchId(), 0, false, "20261101000000", created.id))
        assertEquals(409, other.status)
        assertTrue(other.body.contains("BACKFILL_BUSY"))
        // 続きを再送（重複排除: 同じレコードは増えない）して完了
        val done = it.parse(it.ingest(it.one, raceFetch(fid, 1, true, "20261101000000", created.id)))
        assertEquals("SUCCEEDED", (done["fetch"] as Map<*, *>)["status"])
        assertNull(done["cursor"], "バックフィルは現在のカーソルを動かさない")
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_cursor"))
        assertEquals("COMPLETED", it.db.scalar("SELECT status FROM ingest_backfill WHERE id = ${created.id}"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_gap WHERE id = $gapId AND resolved_at IS NOT NULL"), "取りこぼし区間が解消される")
        assertEquals(
            BackfillStatus.COMPLETED,
            it.ctx
                .getBean(BackfillStore::class.java)
                .find(t1, created.id)!!
                .status,
        )
        // 完了後は同じ要求（種別・option・fromtime）を出し直せる（実行中のものだけが一意）
        requestBackfill("RACE", 2, "20261001000000", gapId)
    }

    @Test
    fun `バックフィルの取得が失敗したら要求はREQUESTEDに戻り、取りこぼし区間は残り、再開できる`() {
        val created = BackfillRequestResult.Created(requestBackfill("YSCH", null, "20260101000000"))
        val fid = it.fetchId()
        // YSCH の実データ形式（保留）で EOF → 取得は失敗
        val env =
            it.envelope(
                dataspec = "YSCH",
                option = null,
                fetch = it.fetch(fid, 0, "20260101", "20261231", created.id),
                records = listOf(it.rec("YS", "x".toByteArray())),
            )
        val r = it.parse(it.ingest(it.one, env))
        assertEquals("FAILED", (r["fetch"] as Map<*, *>)["status"])
        assertEquals("REQUESTED", it.db.scalar("SELECT status FROM ingest_backfill WHERE id = ${created.id}"))
        assertNull(it.db.scalar("SELECT fetch_id FROM ingest_backfill WHERE id = ${created.id}"))
        assertEquals("UNPARSED_RECORDS", it.db.scalar("SELECT fail_reason FROM ingest_backfill WHERE id = ${created.id}"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_gap WHERE reason = 'FETCH_FAILURE' AND resolved_at IS NULL"))
        // 新しい取得セッションで再開できる
        val retry =
            it.ingest(
                it.one,
                it.envelope(
                    dataspec = "YSCH",
                    option = null,
                    fetch = it.fetch(it.fetchId(), 0, "20260101", "20261231", created.id),
                    eof = false,
                    records = listOf(it.nys("20260104")),
                ),
            )
        assertEquals(200, retry.status, retry.body)
        assertEquals("IN_PROGRESS", it.db.scalar("SELECT status FROM ingest_backfill WHERE id = ${created.id}"))
    }

    // ---------------- 並行・デッドロック ----------------

    @Test
    fun `同じ開催日・予定を逆順に更新する同時バッチでもデッドロックで失われず、結果が一貫する`() {
        val venues = listOf("05" to 3, "09" to 2, "08" to 5)
        val results =
            runConcurrently(8) { i ->
                // 偶数番は昇順、奇数番は逆順に同じ 3 つの開催日と 3 つのレースを書く
                val order = if (i % 2 == 0) venues else venues.reversed()
                val recs =
                    order.map { (v, kai) -> it.nys("20261101", venue = v, kai = kai) } +
                        order.map { (v, kai) -> it.nra("20261101${v}11", "2", "20261030", kai = kai) }
                it.ingest(it.one, it.envelope(records = recs))
            }
        val statuses = results.map { r -> r.getOrThrow().status }
        assertTrue(statuses.all { s -> s == 200 || s == 503 }, "$statuses")
        assertTrue(statuses.count { s -> s == 200 } >= 1, "$statuses")
        assertEquals(3, it.count("SELECT COUNT(*) FROM race_day"))
        assertEquals(3, it.count("SELECT COUNT(*) FROM race_schedule"))
        assertEquals(3, it.count("SELECT COUNT(*) FROM race_day WHERE status = 'CONFIRMED'"), "RA が届いた開催日は CONFIRMED（到着順によらない）")
        val dayEvents = it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'DAY_STATUS'")
        assertTrue(dayEvents in 3..6, "履歴は変化ごとに 1 件（場ごとに作成 1〜遷移 1）: $dayEvents")
        assertEquals(dayEvents, it.count("SELECT COUNT(DISTINCT dedup_hash) FROM race_schedule_event WHERE event_type = 'DAY_STATUS'"), "同じ変化の履歴が二重にならない")
        assertEquals(3, it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'ADDED'"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_batch WHERE status = 'PROCESSING'"), "放置された処理が残らない")
        // 503 だったバッチは再送で完了できる
        val sample = it.envelope(records = listOf(it.nys("20261101")))
        assertEquals(200, it.ingest(it.one, sample).status)
    }
}
