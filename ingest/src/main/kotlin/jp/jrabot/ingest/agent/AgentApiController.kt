package jp.jrabot.ingest.agent

import jakarta.servlet.http.HttpServletRequest
import jp.jrabot.domain.ingest.BatchIngestor
import jp.jrabot.domain.ingest.EnvelopeResult
import jp.jrabot.domain.ingest.EnvelopeValidator
import jp.jrabot.domain.ingest.HeartbeatResult
import jp.jrabot.domain.ingest.HeartbeatService
import jp.jrabot.domain.ingest.HeartbeatValidator
import jp.jrabot.domain.ingest.IngestAck
import jp.jrabot.domain.ingest.IngestCaller
import jp.jrabot.domain.ingest.IngestOutcome
import jp.jrabot.domain.ingest.IngestRejection
import jp.jrabot.domain.ingest.IngestRetryableException
import jp.jrabot.domain.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * エージェント向け API の本体（CONTRACTS §3.2・§3.4・§3.5）。呼ばれた時点で、運用モードは NORMAL、呼び出し元は認証済み
 * （[AgentAuthFilter]。テナントは認証したエージェントの行から決まり、本文の値は使わない）。
 *
 * 応答の本文にはエラーコードの固定語彙だけを出す（入力の本文・例外メッセージ・SQL・接続情報を返さない）。
 */
@RestController
@RequestMapping("/v1/agent")
class AgentApiController(
    private val ingestor: BatchIngestor,
    private val heartbeats: HeartbeatService,
    private val validator: EnvelopeValidator,
    private val json: JsonTrees,
    private val clock: Clock,
    private val maxInflatedBytes: Int,
) {
    private val log = LoggerFactory.getLogger(AgentApiController::class.java)

    @PostMapping("/ingest")
    fun ingest(request: HttpServletRequest): ResponseEntity<String> {
        val caller = request.getAttribute(AgentAuthFilter.ATTR_CALLER) as IngestCaller
        val tree =
            when (val r = readTree(request, maxInflatedBytes)) {
                is Body.Tree -> r.value
                is Body.Failed -> return error(r.rejection)
            }
        val envelope =
            when (val v = validator.validate(tree, caller.agentId, clock.now())) {
                is EnvelopeResult.Valid -> v.envelope
                is EnvelopeResult.Invalid -> return error(v.rejection)
            }
        return when (val out = ingestor.ingest(caller, envelope)) {
            is IngestOutcome.Accepted -> ok(ackBody(out.ack))
            is IngestOutcome.Rejected -> error(out.rejection)
        }
    }

    @PostMapping("/heartbeat")
    fun heartbeat(request: HttpServletRequest): ResponseEntity<String> {
        val caller = request.getAttribute(AgentAuthFilter.ATTR_CALLER) as IngestCaller
        val tree =
            when (val r = readTree(request, MAX_HEARTBEAT_BYTES)) {
                is Body.Tree -> r.value
                is Body.Failed -> return error(r.rejection)
            }
        val report =
            when (val v = HeartbeatValidator.validate(tree)) {
                is HeartbeatResult.Valid -> v.report
                is HeartbeatResult.Invalid -> return error(v.rejection)
            }
        heartbeats.record(caller, report)
        return ok(json.write(mapOf("serverTime" to clock.now().toString())))
    }

    private sealed interface Body {
        data class Tree(
            val value: Any?,
        ) : Body

        data class Failed(
            val rejection: IngestRejection,
        ) : Body
    }

    private fun readTree(
        request: HttpServletRequest,
        maxBytes: Int,
    ): Body {
        val type = request.contentType?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
        if (type == null || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)) return Body.Failed(IngestRejection(400, "INVALID_CONTENT_TYPE"))
        val raw = request.getAttribute(AgentAuthFilter.ATTR_BODY) as ByteArray
        val plain =
            when (request.getHeader(HttpHeaders.CONTENT_ENCODING)?.trim()?.lowercase()) {
                null, "", "identity" -> {
                    if (raw.size > maxBytes) return Body.Failed(IngestRejection(413, "BODY_TOO_LARGE")) else raw
                }

                "gzip" -> {
                    try {
                        BodyDecoder.gunzip(raw, maxBytes) ?: return Body.Failed(IngestRejection(413, "BODY_TOO_LARGE_INFLATED"))
                    } catch (_: MalformedBodyException) {
                        return Body.Failed(IngestRejection(400, "INVALID_GZIP"))
                    }
                }

                else -> {
                    return Body.Failed(IngestRejection(400, "UNSUPPORTED_CONTENT_ENCODING"))
                }
            }
        return try {
            Body.Tree(json.parse(plain))
        } catch (_: MalformedBodyException) {
            Body.Failed(IngestRejection(400, "INVALID_JSON"))
        }
    }

    private fun ackBody(a: IngestAck): String {
        val m = linkedMapOf<String, Any?>()
        m["accepted"] = a.accepted
        m["duplicates"] = a.duplicates
        m["quarantined"] = a.quarantined
        m["unsupported"] = a.unsupported
        m["duplicate"] = a.duplicateBatch
        m["ackTs"] = a.ackTs.toString()
        m["serverTime"] = clock.now().toString()
        m["fetch"] = a.fetch?.let { mapOf("id" to it.fetchId, "status" to it.status.name, "nextSeq" to it.nextSeq) }
        m["cursor"] = a.cursor?.let { mapOf("dataType" to it.dataType, "lastFileTimestamp" to it.lastFileTimestamp) }
        return json.write(m)
    }

    private fun ok(body: String): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body)

    private fun error(r: IngestRejection): ResponseEntity<String> {
        val b =
            ResponseEntity
                .status(r.status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
        if (r.status == 409 || r.status == 429) b.header(HttpHeaders.RETRY_AFTER, "5")
        // detail は項目名など固定語彙（入力の値は入れていない）
        return b.body(json.write(if (r.detail == null) mapOf("error" to r.code) else mapOf("error" to r.code, "detail" to r.detail)))
    }

    /** 予期しない例外。**本文・例外メッセージは返さない**。再試行で解消し得る DB の失敗は 503（Retry-After）、それ以外は 500。 */
    @ExceptionHandler(RuntimeException::class)
    fun unexpected(e: RuntimeException): ResponseEntity<String> {
        val sql = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()
        val retryable =
            e is IngestRetryableException || e is ConcurrencyFailureException || e is TransientDataAccessException ||
                e is DataAccessResourceFailureException || e is CannotGetJdbcConnectionException ||
                (sql != null && (sql.errorCode in RETRYABLE_SQL_CODES || sql.sqlState?.startsWith("08") == true || sql is java.sql.SQLTransientException))
        // 例外の型と SQL のエラー番号だけを出す。メッセージ（SQL・値・接続情報を含み得る）は出さない
        log.error("agent api failure: {} sqlCode={}", e.javaClass.name, sql?.errorCode)
        val b =
            ResponseEntity
                .status(if (retryable) 503 else 500)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
        if (retryable) b.header(HttpHeaders.RETRY_AFTER, "5")
        return b.body(if (retryable) """{"error":"UNAVAILABLE"}""" else """{"error":"INTERNAL"}""")
    }

    private companion object {
        const val MAX_HEARTBEAT_BYTES = 64 * 1024

        /** 再試行で解消し得る MySQL のエラー: 1213 デッドロック、1205 ロック待ちのタイムアウト、1040 接続数超過。 */
        val RETRYABLE_SQL_CODES = setOf(1213, 1205, 1040)
    }
}
