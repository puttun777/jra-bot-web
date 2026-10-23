package jp.jrabot.cloudgcp.ops

import jp.jrabot.domain.ops.OpsMode
import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachingModeProviderTest {
    private val clock = MutableClock()
    private val log = RecordingLogger()

    private fun provider(source: OpsStateSource) = CachingModeProvider(source, clock, log = log)

    @Test
    fun `正常系1-4 モードを返す`() {
        for (m in listOf("NORMAL", "STARTING", "STOPPING", "STOPPED")) {
            val p = provider(ScriptedSource(body(opsStateJson(mode = m))))
            assertEquals(OpsMode.fromSourceValue(m), p.current(), m)
        }
    }

    @Test
    fun `正常系5 seq が増えたら新しい状態を受理する`() {
        val source = ScriptedSource(body(opsStateJson(seq = 1)), body(opsStateJson(seq = 2, mode = "STOPPING")))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(15)
        assertEquals(OpsMode.STOPPING, p.current())
    }

    @Test
    fun `正常系6 同じ seq で同じ内容は正常（再取得）`() {
        val source = ScriptedSource(body(opsStateJson(seq = 5)))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(15)
        assertEquals(OpsMode.NORMAL, p.current())
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `同じ seq で mode が違うのは契約違反として UNKNOWN`() {
        val source = ScriptedSource(body(opsStateJson(seq = 5)), body(opsStateJson(seq = 5, mode = "STOPPED", desiredMode = "STOPPED")))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(15)
        assertEquals(OpsMode.UNKNOWN, p.current())
        assertTrue(
            log
                .at(Level.WARN)
                .single()
                .message
                .contains("SEQ_CONFLICT"),
        )
    }

    @Test
    fun `正常系8 15 秒以内はキャッシュを使い、取得は 1 回`() {
        val source = ScriptedSource(body(opsStateJson()))
        val p = provider(source)
        repeat(3) {
            assertEquals(OpsMode.NORMAL, p.current())
            clock.advanceMillis(4_900)
        }
        // 経過 14.7 秒
        assertEquals(OpsMode.NORMAL, p.current())
        assertEquals(1, source.reads.get())
    }

    @Test
    fun `正常系9 15 秒ちょうどで再取得し、新しいモードを返す`() {
        val source = ScriptedSource(body(opsStateJson(seq = 1)), body(opsStateJson(seq = 2, mode = "STOPPED", desiredMode = "STOPPED")))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceMillis(14_999)
        assertEquals(OpsMode.NORMAL, p.current())
        assertEquals(1, source.reads.get())
        clock.advanceMillis(1)
        assertEquals(OpsMode.STOPPED, p.current())
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `異常系15 seq の巻き戻りは UNKNOWN で、キャッシュせず、WARN を出す`() {
        val source = ScriptedSource(body(opsStateJson(seq = 10)), body(opsStateJson(seq = 9)))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(15)
        assertEquals(OpsMode.UNKNOWN, p.current())
        val warns = log.at(Level.WARN)
        assertEquals(1, warns.size)
        assertTrue(warns.single().message.contains("SEQ_ROLLBACK"))
        // 失敗はキャッシュされない: 5 秒後の再取得でも（正本が同じ巻き戻りのままなら）UNKNOWN のまま、取得は走る
        clock.advanceSeconds(5)
        assertEquals(OpsMode.UNKNOWN, p.current())
        assertEquals(3, source.reads.get())
    }

    @Test
    fun `seq の巻き戻り検知はキャッシュ切れ・取得失敗をまたいで有効`() {
        val source =
            ScriptedSource(
                body(opsStateJson(seq = 10)),
                fail(OpsFailureReason.SOURCE_UNAVAILABLE),
                body(opsStateJson(seq = 3)), // 失敗のあと、古い seq の NORMAL
                body(opsStateJson(seq = 11, mode = "STOPPED", desiredMode = "STOPPED")),
            )
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(15)
        assertEquals(OpsMode.UNKNOWN, p.current()) // 取得失敗
        clock.advanceSeconds(5)
        assertEquals(OpsMode.UNKNOWN, p.current()) // seq=3 < 10: 巻き戻り
        clock.advanceSeconds(5)
        assertEquals(OpsMode.STOPPED, p.current()) // seq=11 で復帰
    }

    @Test
    fun `異常系16-19 読取りの失敗はすべて UNKNOWN`() {
        for (reason in OpsFailureReason.entries.filter { it.name.startsWith("SOURCE_") }) {
            val p = provider(ScriptedSource(fail(reason)))
            assertEquals(OpsMode.UNKNOWN, p.current(), reason.name)
        }
    }

    @Test
    fun `異常系20 キャッシュ期限切れ後の障害では直前の NORMAL を使わず UNKNOWN`() {
        val source = ScriptedSource(body(opsStateJson()), fail(OpsFailureReason.SOURCE_TIMEOUT))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceMillis(14_999)
        assertEquals(OpsMode.NORMAL, p.current()) // まだ有効
        clock.advanceMillis(1)
        assertEquals(OpsMode.UNKNOWN, p.current())
    }

    @Test
    fun `異常系21 失敗後 5 秒以内は取得せず UNKNOWN、5 秒で再試行`() {
        val source = ScriptedSource(fail(OpsFailureReason.SOURCE_UNAVAILABLE), body(opsStateJson()))
        val p = provider(source)
        assertEquals(OpsMode.UNKNOWN, p.current())
        assertEquals(1, source.reads.get())
        clock.advanceMillis(4_999)
        repeat(5) { assertEquals(OpsMode.UNKNOWN, p.current()) }
        assertEquals(1, source.reads.get(), "5 秒以内は取得しない")
        clock.advanceMillis(1)
        assertEquals(OpsMode.NORMAL, p.current())
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `異常系22 障害から復旧したら再取得して NORMAL に戻り、復旧を INFO に残す`() {
        val source = ScriptedSource(body(opsStateJson(seq = 4)), fail(OpsFailureReason.SOURCE_UNAVAILABLE), body(opsStateJson(seq = 4)))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(15)
        assertEquals(OpsMode.UNKNOWN, p.current())
        clock.advanceSeconds(5)
        assertEquals(OpsMode.NORMAL, p.current())
        val infos = log.at(Level.INFO).map { it.message }
        assertEquals(2, infos.size, "初回と復旧の 2 回: $infos")
    }

    @Test
    fun `異常系25 UNKNOWN では処理の開始を許可しない`() {
        val failures =
            listOf(
                fail(OpsFailureReason.SOURCE_NOT_FOUND),
                body("not json"),
                body(opsStateJson(mode = "UNKNOWN")),
                body(opsStateJson().replace("\"schema_version\": 1", "\"schema_version\": 2")),
            )
        for (f in failures) {
            val mode = provider(ScriptedSource(f)).current()
            assertEquals(OpsMode.UNKNOWN, mode)
            assertFalse(mode.allowsWork)
            assertFalse(mode.isConfirmed)
        }
        for (m in listOf("STARTING", "STOPPING", "STOPPED")) {
            assertFalse(provider(ScriptedSource(body(opsStateJson(mode = m)))).current().allowsWork, m)
        }
        assertTrue(provider(ScriptedSource(body(opsStateJson()))).current().allowsWork)
    }

    @Test
    fun `時計が戻ったらキャッシュを使い続けない`() {
        val source = ScriptedSource(body(opsStateJson()))
        val p = provider(source)
        p.current()
        clock.advanceSeconds(-1)
        p.current()
        assertEquals(2, source.reads.get())
    }

    @Test
    fun `失敗したら以前の成功値を残さない（時計が戻って失敗したあと、元の期限内でも NORMAL を返さない）`() {
        val source = ScriptedSource(body(opsStateJson()), fail(OpsFailureReason.SOURCE_UNAVAILABLE))
        val p = provider(source)
        assertEquals(OpsMode.NORMAL, p.current())
        clock.advanceSeconds(-1)
        assertEquals(OpsMode.UNKNOWN, p.current()) // 時計が戻ったので再取得し、失敗
        clock.advanceSeconds(6) // 最初の取得から 5 秒後（元のキャッシュの期限内）、再試行の間隔も過ぎた
        assertEquals(OpsMode.UNKNOWN, p.current())
        assertEquals(3, source.reads.get())
    }

    @Test
    fun `ログ 状態が変わったときだけ INFO、キャッシュ利用では出さない`() {
        val source =
            ScriptedSource(
                body(opsStateJson(seq = 1)),
                body(opsStateJson(seq = 1)),
                body(opsStateJson(seq = 2, mode = "STOPPING", desiredMode = "STOPPED")),
            )
        val p = provider(source)
        repeat(50) { p.current() }
        clock.advanceSeconds(15)
        repeat(50) { p.current() } // seq=1 を再取得: 変化なし
        clock.advanceSeconds(15)
        repeat(50) { p.current() }
        val infos = log.at(Level.INFO).map { it.message }
        assertEquals(listOf("ops mode: mode=NORMAL seq=1", "ops mode: mode=STOPPING seq=2"), infos)
        assertTrue(log.at(Level.WARN).isEmpty())
    }

    @Test
    fun `ログ 同じ原因の UNKNOWN が続いても WARN は繰り返さず、原因が変われば出す`() {
        val source =
            ScriptedSource(
                fail(OpsFailureReason.SOURCE_UNAVAILABLE),
                fail(OpsFailureReason.SOURCE_UNAVAILABLE),
                fail(OpsFailureReason.SOURCE_UNAVAILABLE),
                fail(OpsFailureReason.SOURCE_PERMISSION_DENIED),
            )
        val p = provider(source)
        repeat(3) {
            p.current()
            p.current() // 5 秒以内: 取得もログもなし
            clock.advanceSeconds(5)
        }
        p.current()
        val warns = log.at(Level.WARN).map { it.message }
        assertEquals(2, warns.size, "$warns")
        assertTrue(warns[0].contains("SOURCE_UNAVAILABLE"))
        assertTrue(warns[1].contains("SOURCE_PERMISSION_DENIED"))
        assertTrue(log.entries.none { it.hasThrowable }, "スタックトレースを出さない")
    }

    @Test
    fun `ログ 長く続く障害は一定間隔で WARN を再掲する`() {
        val source = ScriptedSource(fail(OpsFailureReason.SOURCE_UNAVAILABLE))
        val p = provider(source)
        repeat(61) {
            p.current()
            clock.advanceSeconds(5)
        } // 305 秒
        assertEquals(2, log.at(Level.WARN).size)
    }

    @Test
    fun `ログに JSON の内容を出さない`() {
        val secret = "TOP-SECRET-VALUE-123"
        val bad = opsStateJson(mode = secret, extra = ", \"token\": \"$secret\"")
        val p = provider(ScriptedSource(body(bad), body("{\"token\":\"$secret\" broken"), body(opsStateJson(extra = ", \"token\": \"$secret\""))))
        p.current()
        clock.advanceSeconds(5)
        p.current()
        clock.advanceSeconds(5)
        assertEquals(OpsMode.NORMAL, p.current())
        assertTrue(log.entries.none { it.message.contains(secret) }, log.entries.toString())
    }

    @Test
    fun `実装の不具合（読取りの契約外の例外）は握りつぶさず伝播する`() {
        val p = provider(OpsStateSource { throw IllegalStateException("bug") })
        val e = runCatching { p.current() }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        // ロックは解放され、次の呼び出しも同じ結果になる（デッドロックしない）
        assertTrue(runCatching { p.current() }.exceptionOrNull() is IllegalStateException)
    }
}
