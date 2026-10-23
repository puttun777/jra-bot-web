package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Base64

/** 封筒の `class`。S=蓄積系（再取得できる）、R=速報（時系列。二度と取れない）。C（制御）はキューに溜めないので封筒には現れない。 */
enum class BatchClass(
    val code: String,
) {
    STORED("S"),
    REALTIME("R"),
    ;

    companion object {
        fun fromCodeOrNull(code: String): BatchClass? = entries.firstOrNull { it.code == code }
    }
}

enum class FetchOrigin { LIVE, BACKFILL }

/**
 * 取得セッション（JVOpen 1 回分）の情報。**プロトコルへの追加（提案 P-2。未承認。D-134）**で、封筒に無ければ「取得の完了を追跡しない」
 * （データは保存・適用するが、取得の事実もカーソルの前進も作らない）。[seq] は 0 始まりの連番で、EOF のバッチが最後。
 */
data class FetchInfo(
    val fetchId: String,
    val seq: Int,
    val targetFrom: LocalDate?,
    val targetTo: LocalDate?,
    val origin: FetchOrigin,
    val backfillId: Long?,
)

class IngestRecordIn(
    val id: String,
    val bytes: ByteArray,
    /** エージェントが申告し、サーバが検証した SHA-256（小文字 16 進）。 */
    val bytesSha256: String,
) {
    override fun equals(other: Any?) = other is IngestRecordIn && id == other.id && bytesSha256 == other.bytesSha256

    override fun hashCode() = 31 * id.hashCode() + bytesSha256.hashCode()

    override fun toString() = "IngestRecordIn(id=$id, bytes=${bytes.size}B, sha256=${bytesSha256.take(8)}…)"
}

/**
 * 検証済みの ingest 封筒（CONTRACTS §3.4）。**テナントは持たない**: テナントは認証したエージェントの行から引く（本文の値は信用しない。D-131）。
 */
class IngestEnvelope(
    val agentId: AgentId,
    val batchId: String,
    val batchClass: BatchClass,
    val observedAt: Instant,
    val dataspec: String,
    val option: Int?,
    val fromtime: String?,
    val lastFileTimestamp: String?,
    val eof: Boolean,
    val fetch: FetchInfo?,
    val records: List<IngestRecordIn>,
) {
    /**
     * バッチ内容の指紋。**同じ内容の再送かどうか**を判定する（同じ `batchId` で内容が違えば不正な再利用）。JSON の整形や gzip の違いに依存しないよう、
     * 検証済みの項目と各レコードの SHA-256 から作る。
     */
    val payloadSha256: String by lazy {
        val sb = StringBuilder("jra-bot/ingest-batch/v1\n")
        sb
            .append(agentId.value)
            .append('\n')
            .append(batchClass.code)
            .append('\n')
            .append(observedAt)
            .append('\n')
        sb
            .append(dataspec)
            .append('\n')
            .append(option ?: "-")
            .append('\n')
            .append(fromtime ?: "-")
            .append('\n')
        sb
            .append(lastFileTimestamp ?: "-")
            .append('\n')
            .append(eof)
            .append('\n')
        if (fetch == null) {
            sb.append("-\n")
        } else {
            sb
                .append(fetch.fetchId)
                .append(',')
                .append(fetch.seq)
                .append(',')
                .append(fetch.targetFrom ?: "-")
                .append(',')
                .append(fetch.targetTo ?: "-")
                .append(',')
                .append(fetch.origin)
                .append(',')
                .append(fetch.backfillId ?: "-")
                .append('\n')
        }
        records.forEach {
            sb
                .append(it.id)
                .append(':')
                .append(it.bytesSha256)
                .append('\n')
        }
        Hashing.sha256Hex(sb.toString().toByteArray(Charsets.UTF_8))
    }
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * `ingest_raw.record_hash`（冪等キー）。蓄積系（S）は同じバイト列なら同じハッシュ（重なりを持たせた再取得を吸収）。
     * 速報（R）は**観測時刻を含める**（同じオッズが別の時刻に観測されたものは、別の時系列の点であって重複ではない）。
     */
    fun recordHash(
        dataspec: String,
        recordId: String,
        batchClass: BatchClass,
        observedAt: Instant,
        bytesSha256: String,
    ): String =
        sha256Hex(
            "jra-bot/ingest-record/v1\n$dataspec\n$recordId\n${batchClass.code}\n${if (batchClass == BatchClass.REALTIME) observedAt.toString() else "-"}\n$bytesSha256"
                .toByteArray(Charsets.UTF_8),
        )
}

/** 封筒の拒否。HTTP の状態コードは CONTRACTS §3.6（400/413/422 は不正な封筒 = バグ。エージェントは DEAD にしてリトライしない）。 */
data class IngestRejection(
    val status: Int,
    val code: String,
    val detail: String? = null,
) {
    init {
        require(status in setOf(400, 403, 409, 413, 422, 429, 503)) { "想定外の状態コード: $status" }
    }
}

sealed interface EnvelopeResult {
    data class Valid(
        val envelope: IngestEnvelope,
    ) : EnvelopeResult

    data class Invalid(
        val rejection: IngestRejection,
    ) : EnvelopeResult
}

/** 受け付けるデータ種別。値は参照元の使用実態（JVLINK_DESIGN §1.2。**二次情報**で、公式の一覧は T1.17 で確認）。 */
object SupportedDataSpecs {
    val stored: Set<String> = setOf("RACE", "YSCH", "RCVN", "RCOV")
    val realtime: Set<String> = setOf("0B11", "0B12", "0B14", "0B15", "0B30", "0B31", "0B33", "0B35")

    fun classOf(dataspec: String): BatchClass? =
        when (dataspec) {
            in stored -> BatchClass.STORED
            in realtime -> BatchClass.REALTIME
            else -> null
        }
}

data class EnvelopeLimits(
    val maxRecords: Int = 200,
    val maxRecordBytes: Int = 256 * 1024,
    /** 観測時刻がサーバ時刻よりこれ以上先なら拒否する（エージェントの時計ずれ。認証の ±300 秒と同じ）。 */
    val maxFutureSkew: Duration = Duration.ofSeconds(300),
    /** 速報（R）の観測がこれより古ければ拒否する（キューの TTL 7 日。JVLINK_DESIGN §5.3）。蓄積系は古くてよい。 */
    val maxRealtimeAge: Duration = Duration.ofDays(7),
) {
    init {
        require(maxRecords in 1..1000) { "maxRecords は 1〜1000" }
        require(maxRecordBytes in 1..(8 * 1024 * 1024)) { "maxRecordBytes は 1〜8MiB" }
    }
}

/**
 * JSON を読んだ木（`Map` / `List` / `String` / `Number` / `Boolean` / `null`）から封筒を検証して作る。JSON ライブラリには依存しない
 * （読み取りは :ingest のアダプタ）。**未知のキーは拒否**する（`tenantId` などでテナントを指示する試みを含む）。
 */
class EnvelopeValidator(
    private val limits: EnvelopeLimits = EnvelopeLimits(),
) {
    fun validate(
        tree: Any?,
        authenticatedAgent: AgentId,
        now: Instant,
    ): EnvelopeResult =
        try {
            EnvelopeResult.Valid(build(tree, authenticatedAgent, now))
        } catch (r: Reject) {
            EnvelopeResult.Invalid(r.rejection)
        }

    private class Reject(
        val rejection: IngestRejection,
    ) : RuntimeException(null, null, false, false)

    private fun reject(
        status: Int,
        code: String,
        detail: String? = null,
    ): Nothing = throw Reject(IngestRejection(status, code, detail))

    private fun build(
        tree: Any?,
        authenticatedAgent: AgentId,
        now: Instant,
    ): IngestEnvelope {
        val root = asObject(tree, "envelope")
        checkKeys(root, TOP_KEYS, "envelope")
        val protocol = root["protocol"] ?: reject(400, "MISSING_FIELD", "protocol")
        if (protocol !is Number || protocol.toDouble() != protocol.toLong().toDouble()) reject(400, "INVALID_TYPE", "protocol")
        if (protocol.toLong() != 1L) reject(422, "UNSUPPORTED_PROTOCOL", protocol.toString())

        val agentText = text(root, "agentId")
        val agentId = AgentId.ofOrNull(agentText) ?: reject(422, "INVALID_AGENT_ID")
        if (agentId != authenticatedAgent) reject(403, "AGENT_MISMATCH") // 認証したエージェントと違う主体を名乗る本文は拒否する

        val batchId = text(root, "batchId")
        if (!UUID_V7.matches(batchId)) reject(422, "INVALID_BATCH_ID")

        val batchClass = BatchClass.fromCodeOrNull(text(root, "class")) ?: reject(422, "INVALID_CLASS")

        val observedText = text(root, "observedAt")
        val observedAt =
            try {
                Instant.parse(observedText)
            } catch (_: DateTimeParseException) {
                reject(422, "INVALID_OBSERVED_AT")
            }
        if (!observedText.endsWith("Z")) reject(422, "INVALID_OBSERVED_AT", "UTC（Z）で表す")
        if (observedAt.isAfter(now.plus(limits.maxFutureSkew))) reject(422, "OBSERVED_AT_IN_FUTURE")
        if (batchClass == BatchClass.REALTIME && observedAt.isBefore(now.minus(limits.maxRealtimeAge))) reject(422, "OBSERVED_AT_TOO_OLD")

        val dataspec = text(root, "dataspec")
        val specClass = SupportedDataSpecs.classOf(dataspec) ?: reject(422, "UNSUPPORTED_DATASPEC")
        if (specClass != batchClass) reject(422, "CLASS_DATASPEC_MISMATCH")

        val option = optionalInt(root, "option")
        if (option != null && option !in 0..9) reject(422, "INVALID_OPTION")
        val fromtime = optionalTimestamp(root, "fromtime")
        val lastFileTimestamp = optionalTimestamp(root, "lastFileTimestamp")
        val eof = root["eof"] as? Boolean ?: reject(400, "INVALID_TYPE", "eof")

        val fetch = root["fetch"]?.let { parseFetch(it, batchClass) }

        val rawRecords = root["records"] as? List<*> ?: reject(400, "INVALID_TYPE", "records")
        if (rawRecords.size > limits.maxRecords) reject(413, "TOO_MANY_RECORDS", "${rawRecords.size} > ${limits.maxRecords}")
        if (rawRecords.isEmpty() && !eof) reject(422, "EMPTY_BATCH")
        val records = rawRecords.mapIndexed { i, r -> parseRecord(r, i) }
        return IngestEnvelope(agentId, batchId, batchClass, observedAt, dataspec, option, fromtime, lastFileTimestamp, eof, fetch, records)
    }

    private fun parseFetch(
        node: Any,
        batchClass: BatchClass,
    ): FetchInfo {
        if (batchClass != BatchClass.STORED) reject(422, "FETCH_NOT_APPLICABLE", "fetch は蓄積系（S）だけ")
        val o = asObject(node, "fetch")
        checkKeys(o, FETCH_KEYS, "fetch")
        val id = text(o, "id")
        if (!UUID_ANY.matches(id)) reject(422, "INVALID_FETCH_ID")
        val seq = optionalInt(o, "seq") ?: reject(400, "MISSING_FIELD", "fetch.seq")
        if (seq < 0) reject(422, "INVALID_FETCH_SEQ")
        val from = optionalDate(o, "targetFrom")
        val to = optionalDate(o, "targetTo")
        if ((from == null) != (to == null)) reject(422, "INVALID_FETCH_RANGE", "targetFrom と targetTo は両方指定する")
        if (from != null && to != null && to.isBefore(from)) reject(422, "INVALID_FETCH_RANGE")
        val origin =
            when (val s = o["origin"]) {
                null -> FetchOrigin.LIVE
                is String -> FetchOrigin.entries.firstOrNull { it.name == s } ?: reject(422, "INVALID_FETCH_ORIGIN")
                else -> reject(400, "INVALID_TYPE", "fetch.origin")
            }
        val backfillId = o["backfillId"]?.let { v -> (v as? Number)?.toLong()?.takeIf { it > 0 } ?: reject(422, "INVALID_BACKFILL_ID") }
        if ((origin == FetchOrigin.BACKFILL) != (backfillId != null)) reject(422, "INVALID_BACKFILL_ID", "BACKFILL と backfillId は対")
        return FetchInfo(id, seq, from, to, origin, backfillId)
    }

    private fun parseRecord(
        node: Any?,
        index: Int,
    ): IngestRecordIn {
        val o = asObject(node, "records[$index]")
        checkKeys(o, RECORD_KEYS, "records[$index]")
        val id = text(o, "id")
        if (!RECORD_ID.matches(id)) reject(422, "INVALID_RECORD_ID", "records[$index]")
        val b64 = text(o, "bytesB64")
        // 厳密な標準 Base64（改行・空白・URL セーフ文字を許さない）。復号前に長さで上限を見る（巨大な割当てを避ける）
        if (b64.length > limits.maxRecordBytes / 3 * 4 + 8) reject(413, "RECORD_TOO_LARGE", "records[$index]")
        if (!BASE64.matches(b64)) reject(422, "INVALID_BASE64", "records[$index]")
        val bytes =
            try {
                Base64.getDecoder().decode(b64)
            } catch (_: IllegalArgumentException) {
                reject(422, "INVALID_BASE64", "records[$index]")
            }
        if (bytes.size > limits.maxRecordBytes) reject(413, "RECORD_TOO_LARGE", "records[$index]")
        if (bytes.isEmpty()) reject(422, "EMPTY_RECORD", "records[$index]")
        val sha = text(o, "sha256")
        if (!SHA256.matches(sha)) reject(422, "INVALID_SHA256", "records[$index]")
        if (Hashing.sha256Hex(bytes) != sha) reject(422, "RECORD_SHA256_MISMATCH", "records[$index]")
        return IngestRecordIn(id, bytes, sha)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asObject(
        node: Any?,
        what: String,
    ): Map<String, Any?> = node as? Map<String, Any?> ?: reject(400, "INVALID_TYPE", what)

    private fun checkKeys(
        o: Map<String, Any?>,
        allowed: Set<String>,
        what: String,
    ) {
        val unknown = o.keys.firstOrNull { it !in allowed } ?: return
        // テナントを本文で指示する試みは、理由を区別して拒否する（誤って受け入れないことのテストの対象）
        if (unknown.equals("tenantId", true) || unknown.equals("tenant_id", true)) reject(422, "TENANT_FIELD_FORBIDDEN", what)
        reject(422, "UNKNOWN_FIELD", "$what.${unknown.take(32)}")
    }

    private fun text(
        o: Map<String, Any?>,
        key: String,
    ): String {
        if (!o.containsKey(key)) reject(400, "MISSING_FIELD", key)
        return o[key] as? String ?: reject(400, "INVALID_TYPE", key)
    }

    private fun optionalInt(
        o: Map<String, Any?>,
        key: String,
    ): Int? {
        val v = o[key] ?: return null
        if (v !is Number) reject(400, "INVALID_TYPE", key)
        val l = v.toLong()
        if (l.toDouble() != v.toDouble() || l !in Int.MIN_VALUE..Int.MAX_VALUE) reject(422, "INVALID_NUMBER", key)
        return l.toInt()
    }

    private fun optionalTimestamp(
        o: Map<String, Any?>,
        key: String,
    ): String? {
        val v = o[key] ?: return null
        if (v !is String) reject(400, "INVALID_TYPE", key)
        if (!isJvTimestamp(v)) reject(422, "INVALID_TIMESTAMP", key)
        return v
    }

    private fun optionalDate(
        o: Map<String, Any?>,
        key: String,
    ): LocalDate? {
        val v = o[key] ?: return null
        if (v !is String) reject(400, "INVALID_TYPE", key)
        return try {
            LocalDate.parse(v, DATE8)
        } catch (_: DateTimeParseException) {
            reject(422, "INVALID_DATE", key)
        }
    }

    companion object {
        private val TOP_KEYS =
            setOf("protocol", "agentId", "batchId", "class", "observedAt", "dataspec", "option", "fromtime", "lastFileTimestamp", "eof", "records", "fetch")
        private val FETCH_KEYS = setOf("id", "seq", "targetFrom", "targetTo", "origin", "backfillId")
        private val RECORD_KEYS = setOf("id", "bytesB64", "sha256")
        val UUID_V7 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val UUID_ANY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val RECORD_ID = Regex("[A-Z0-9]{2,4}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
        private val DATE8: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
        private val TS14: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)

        /** JV-Link のタイムスタンプ（`yyyyMMddHHmmss`、実在する日時）。 */
        fun isJvTimestamp(text: String): Boolean =
            text.length == 14 &&
                try {
                    LocalDateTime.parse(text, TS14).year >= 1900
                } catch (_: DateTimeParseException) {
                    false
                }
    }
}
