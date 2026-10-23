package jp.jrabot.ingest.agent

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** 本文の展開（gzip）。上限を超えたら null（展開爆弾の対策。上限は呼び出し側が決める）。不正な gzip は例外。 */
object BodyDecoder {
    fun gunzip(
        compressed: ByteArray,
        maxOut: Int,
    ): ByteArray? {
        try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                val out = ByteArrayOutputStream(minOf(maxOut, compressed.size * 8))
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxOut) return null
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: ZipException) {
            throw MalformedBodyException("gzip が不正", e)
        } catch (e: java.io.EOFException) {
            throw MalformedBodyException("gzip が途中で切れている", e)
        }
    }
}

class MalformedBodyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** JSON を `Map` / `List` / `String` / `Number` / `Boolean` / `null` の木へ。重複キー・末尾の余分なトークン・深すぎる入れ子・長すぎる文字列を拒否する。 */
class JsonTrees(
    maxStringLength: Int,
) {
    private val mapper: JsonMapper =
        JsonMapper
            .builder(
                JsonFactory
                    .builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                        StreamReadConstraints
                            .builder()
                            .maxNestingDepth(MAX_DEPTH)
                            .maxStringLength(maxStringLength)
                            .maxNumberLength(MAX_NUMBER)
                            .build(),
                    ).build(),
            ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    fun parse(bytes: ByteArray): Any? =
        try {
            mapper.readValue(bytes, Any::class.java)
        } catch (e: JacksonException) {
            throw MalformedBodyException("JSON が不正", e)
        }

    fun write(value: Any?): String = mapper.writeValueAsString(value)

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_NUMBER = 40
    }
}
