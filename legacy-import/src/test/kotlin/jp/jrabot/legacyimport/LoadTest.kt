package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** load（D-275）の DB を使わない検査: 接続先の URL と、manifest の表・列の照合。 */
class LoadTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `接続先はループバックの host 1 つと許可したパラメータだけ`() {
        for (ok in listOf(
            "jdbc:mysql://localhost:3306/jrabot",
            "jdbc:mysql://127.0.0.1/jrabot",
            "jdbc:mysql://[::1]:3306/jrabot",
            "jdbc:mysql://LOCALHOST:3306/jrabot",
            "jdbc:mysql://localhost:3306/lit1?sslMode=DISABLED&allowPublicKeyRetrieval=true",
        )) {
            assertNull(Load.checkUrl(ok), ok)
        }
        for (bad in listOf(
            "jdbc:mysql://10.0.0.5:3306/jrabot",
            "jdbc:mysql://db.example.com/jrabot",
            "jdbc:mysql://localhost.evil.com/jrabot",
            "jdbc:mysql://127.0.0.1@evil.com/jrabot",
            "jdbc:mysql://user:pw@localhost/jrabot",
            "jdbc:mysql://localhost,remote/jrabot",
            "jdbc:mysql://localhost:3306,remote:3306/jrabot",
            "jdbc:mysql:loadbalance://localhost/jrabot",
            "jdbc:mysql://address=(host=remote)(port=3306)/jrabot",
            "jdbc:mysql://localhost/jrabot?socksProxyHost=remote&socksProxyPort=1080",
            "jdbc:mysql://localhost/jrabot?socketFactory=com.mysql.cj.protocol.NamedPipeSocketFactory",
            "jdbc:mysql://localhost/jrabot?allowLoadLocalInfile=true",
            "jdbc:mysql://localhost/jrabot?autoDeserialize=true",
            "jdbc:mysql://localhost/",
            "jdbc:mariadb://localhost/jrabot",
        )) {
            assertNotNull(Load.checkUrl(bad), bad)
        }
        val props = Load.connectionProperties("u", "p")
        assertEquals("false", props.getProperty("allowLoadLocalInfile"))
        assertEquals("false", props.getProperty("autoDeserialize"))
    }

    /** DB に触れる前に止まることを確かめるための接続（どの呼出しも失敗）。 */
    private val noDb: Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, _ -> throw AssertionError("DB に触れた: ${m.name}") } as Connection

    private fun transformed(): Path {
        val db = LegacyDbs.create(dir.resolve("jraev.sqlite"))
        Extract.run(db, dir.resolve("ex"))
        Transform(7, ZoneId.of("Asia/Tokyo"), lenientJson = false).run(dir.resolve("ex"), dir.resolve("t"))
        return dir.resolve("t")
    }

    private fun rewrite(
        t: Path,
        change: (MutableList<JsonObject>) -> Unit,
    ) {
        val p = t.resolve(Transform.MANIFEST)
        val m = StrictJson.parse(Files.readString(p)) as JsonObject
        val tables = (m["tables"] as JsonArray).items.map { it as JsonObject }.toMutableList()
        change(tables)
        Files.writeString(p, CanonicalJson.encode(JsonObject(m.fields.toMutableMap().also { it["tables"] = JsonArray(tables) })))
    }

    @Test
    fun `manifest の表・列が変換の表と違えば DB に触れる前に拒否する`() {
        val t = transformed()
        Load(t).let { l -> assertFailsWith<AssertionError> { l.run(noDb) } } // 照合は通り、DB に触れたところで止まる
        rewrite(t) { tables ->
            val i = tables.indexOfFirst { it["target"] == JsonString("bets") }
            tables[i] = JsonObject(tables[i].fields.toMutableMap().also { it["target"] = JsonString("app_user") })
        }
        val e1 = assertFailsWith<InputRejected> { Load(t).run(noDb) }
        assertTrue(e1.message!!.contains("変換の表に無い"), e1.message)

        val t2 = dir.resolve("t").also { }
        rewrite(t2) { tables ->
            val i = tables.indexOfFirst { it["target"] == JsonString("app_user") }
            tables[i] =
                JsonObject(
                    tables[i].fields.toMutableMap().also { f ->
                        f["target"] = JsonString("bets")
                        f["columns"] =
                            JsonArray((f["columns"] as JsonArray).items.map { if (it == JsonString("signal_legacy_id")) JsonString("signal_id") else it })
                    },
                )
        }
        val e2 = assertFailsWith<InputRejected> { Load(t2).run(noDb) }
        assertTrue(e2.message!!.contains("列が変換の表と違う"), e2.message)
    }
}
