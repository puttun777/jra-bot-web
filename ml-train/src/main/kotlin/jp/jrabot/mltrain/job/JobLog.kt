package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 構造化ログ（1 行 1 JSON、標準出力。旧 logsetup.py の移植）。キーは辞書順（正準 JSON）。
 * 秘密・長い内容を出さない（呼び出し側が固定語彙と数値だけを渡す）。名前に password・secret・token・signing_key・private を含むキーの値は `***`。
 */
class JobLog(
    private val out: PrintStream = System.out,
    private val now: () -> Instant = Instant::now,
) {
    fun log(
        event: String,
        level: String = "INFO",
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val m = LinkedHashMap<String, JsonValue>()
        m["ts"] = JsonString(TS.format(now()))
        m["level"] = JsonString(level)
        m["event"] = JsonString(event)
        for ((k, v) in fields) {
            m[k] = if (SENSITIVE.any { it in k.lowercase() }) JsonString("***") else safe(v)
        }
        synchronized(out) {
            out.println(CanonicalJson.encode(JsonObject(m)))
            out.flush()
        }
    }

    private fun safe(v: Any?): JsonValue =
        try {
            ModelJson.of(v)
        } catch (_: MlTrainError) {
            JsonString(v.toString())
        }

    companion object {
        private val SENSITIVE = listOf("password", "secret", "token", "signing_key", "private")
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
