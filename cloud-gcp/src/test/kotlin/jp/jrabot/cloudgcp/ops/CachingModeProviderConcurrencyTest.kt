package jp.jrabot.cloudgcp.ops

import jp.jrabot.domain.ops.OpsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 異常系26: 並列アクセス時のキャッシュ・seq の整合性（実時間の待機なし。ラッチで順序を作る）。 */
class CachingModeProviderConcurrencyTest {
    private val threads = 32

    private fun <T> parallel(
        n: Int,
        task: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            val start = CountDownLatch(1)
            val futures =
                (1..n).map {
                    pool.submit<T> {
                        start.await()
                        task()
                    }
                }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `同時に呼ばれても取得は 1 回で、全員が同じ結果を得る`() {
        val clock = MutableClock()
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source =
            OpsStateSource {
                reads.incrementAndGet()
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
                opsStateJson(seq = 7).toByteArray()
            }
        val p = CachingModeProvider(source, clock, log = RecordingLogger())
        val releaser = Executors.newSingleThreadExecutor()
        try {
            releaser.submit {
                entered.await(30, TimeUnit.SECONDS)
                release.countDown()
            }
            val results = parallel(threads) { p.current() }
            assertEquals(List(threads) { OpsMode.NORMAL }, results)
            assertEquals(1, reads.get())
        } finally {
            releaser.shutdownNow()
        }
    }

    @Test
    fun `失敗中に同時に呼ばれても取得は 1 回で、全員 UNKNOWN`() {
        val clock = MutableClock()
        val source = ScriptedSource(fail(OpsFailureReason.SOURCE_UNAVAILABLE))
        val p = CachingModeProvider(source, clock, log = RecordingLogger())
        val results = parallel(threads) { p.current() }
        assertEquals(List(threads) { OpsMode.UNKNOWN }, results)
        assertEquals(1, source.reads.get())
    }

    @Test
    fun `期限切れの更新を同時に行っても seq が壊れず、巻き戻りは常に検知される`() {
        val clock = MutableClock()
        val seq = AtomicInteger(100)
        val source =
            OpsStateSource {
                // 取得のたびに seq が増える。途中で 1 回だけ古い seq を返す
                val n = seq.incrementAndGet()
                opsStateJson(seq = if (n == 105) 50 else n.toLong()).toByteArray()
            }
        val log = RecordingLogger()
        val p = CachingModeProvider(source, clock, log = log)
        var unknowns = 0
        repeat(10) {
            clock.advanceSeconds(15)
            val results = parallel(threads) { p.current() }
            // 同じ回の呼び出しは、取得 1 回の結果（または失敗後 5 秒抑制）を共有する = 全員同じ値
            assertEquals(1, results.toSet().size, "回 $it: $results")
            if (results.first() == OpsMode.UNKNOWN) unknowns++
        }
        assertEquals(1, unknowns, "seq=50 の巻き戻りだけが UNKNOWN になる")
        assertTrue(
            log
                .at(org.slf4j.event.Level.WARN)
                .single()
                .message
                .contains("SEQ_ROLLBACK"),
        )
    }
}
