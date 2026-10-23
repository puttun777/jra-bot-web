package jp.jrabot.cloudgcp.ops

import jp.jrabot.domain.ops.OpsMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalFileModeProviderTest {
    @TempDir lateinit var dir: Path

    private val clock = MutableClock()

    private fun provider(path: Path) = OpsModeProviders.file(path, clock)

    @Test
    fun `ファイルの NORMAL とそれ以外を判定する`() {
        val f = dir.resolve("ops-state.json")
        for (m in listOf("NORMAL", "STARTING", "STOPPING", "STOPPED")) {
            f.writeText(opsStateJson(seq = 1, mode = m))
            assertEquals(OpsMode.fromSourceValue(m), OpsModeProviders.file(f, clock).current(), m)
        }
    }

    @Test
    fun `異常系23 ファイルが無ければ UNKNOWN`() {
        val p = provider(dir.resolve("none.json"))
        assertEquals(OpsMode.UNKNOWN, p.current())
        val e = assertFailsWith<OpsStateReadException> { FileOpsStateSource(dir.resolve("none.json")).read() }
        assertEquals(OpsFailureReason.SOURCE_NOT_FOUND, e.reason)
    }

    @Test
    fun `異常系24 JSON が不正なら UNKNOWN`() {
        val f = dir.resolve("bad.json")
        f.writeText("{ not json")
        assertEquals(OpsMode.UNKNOWN, provider(f).current())
        f.writeText(opsStateJson(mode = "UNKNOWN"))
        assertEquals(OpsMode.UNKNOWN, OpsModeProviders.file(f, clock).current())
    }

    @Test
    fun `ディレクトリや大きすぎるファイルは UNKNOWN`() {
        assertEquals(OpsMode.UNKNOWN, provider(dir).current())
        val big = dir.resolve("big.json")
        Files.write(big, ByteArray(OpsStateSource.MAX_BYTES + 1) { 'x'.code.toByte() })
        assertEquals(OpsMode.UNKNOWN, provider(big).current())
    }

    @Test
    fun `ファイル実装も GCS 実装と同じキャッシュと seq の検査を使う`() {
        val f = dir.resolve("ops-state.json")
        f.writeText(opsStateJson(seq = 10))
        val p = provider(f)
        assertEquals(OpsMode.NORMAL, p.current())
        f.writeText(opsStateJson(seq = 11, mode = "STOPPED", desiredMode = "STOPPED"))
        assertEquals(OpsMode.NORMAL, p.current(), "15 秒以内はキャッシュ")
        clock.advanceSeconds(15)
        assertEquals(OpsMode.STOPPED, p.current())
        f.writeText(opsStateJson(seq = 2))
        clock.advanceSeconds(15)
        assertEquals(OpsMode.UNKNOWN, p.current(), "巻き戻り")
    }

    // --- 設定（本番でローカルへ勝手にフォールバックしない） ---

    private val env = { source: String?, appEnv: String? ->
        buildMap {
            source?.let { put("OPS_MODE_SOURCE", it) }
            appEnv?.let { put("APP_ENV", it) }
        }
    }

    @Test
    fun `APP_ENV=local のときだけファイルを使える（パスと file URI）`() {
        val f = dir.resolve("ops-state.json")
        f.writeText(opsStateJson())
        assertEquals(OpsMode.NORMAL, OpsModeProviders.fromEnvironment(env(f.toString(), "local"), clock).current())
        assertEquals(OpsMode.NORMAL, OpsModeProviders.fromEnvironment(env(f.toUri().toString(), "local"), clock).current())
    }

    @Test
    fun `本番や APP_ENV 未設定でファイル指定なら起動失敗（フォールバックしない）`() {
        val f = dir.resolve("ops-state.json")
        f.writeText(opsStateJson())
        for (appEnv in listOf(null, "", "prod", "LOCAL", "staging")) {
            assertFailsWith<OpsModeConfigurationException>("APP_ENV=$appEnv") {
                OpsModeProviders.fromEnvironment(env(f.toString(), appEnv), clock)
            }
        }
    }

    @Test
    fun `OPS_MODE_SOURCE の未設定・空・不正は起動失敗`() {
        for (appEnv in listOf("local", "prod", null)) {
            for (src in listOf(null, "", "   ", "http://example.invalid/ops-state.json", "s3://bucket/key", "gs://", "gs://bucket", "gs://B_/x")) {
                assertFailsWith<OpsModeConfigurationException>("[$src] $appEnv") { OpsModeProviders.fromEnvironment(env(src, appEnv), clock) }
            }
        }
    }

    @Test
    fun `gs の指定は APP_ENV に関わらず GCS 実装で、生成時には通信しない`() {
        for (appEnv in listOf("prod", "local", null)) {
            // 生成が成功すること（通信・認証情報の取得は最初の current() まで行わない）
            OpsModeProviders.fromEnvironment(env("gs://my-prj-ops-state/state/ops-state.json", appEnv), clock)
        }
    }

    @Test
    fun `設定エラーのメッセージに設定値を含めない`() {
        val e = assertFailsWith<OpsModeConfigurationException> { OpsModeProviders.fromEnvironment(env("/secret/path/ops.json", "prod"), clock) }
        assertTrue(e.message?.contains("/secret/path") != true)
    }
}
