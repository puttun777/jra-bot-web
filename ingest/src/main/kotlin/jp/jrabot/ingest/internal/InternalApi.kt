package jp.jrabot.ingest.internal

import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.persistence.schema.AppBuildInfo
import jp.jrabot.persistence.schema.SchemaCheck
import jp.jrabot.persistence.schema.SchemaFailure
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * プロセスの生存確認（CONTRACTS §3.2）。**DB・GCS・ModeProvider のどれにも依存しない**（このクラスは [AppBuildInfo] しか受け取らない）。
 * DB が停止していても、プロセスが動いていれば 200。
 */
@RestController
@RequestMapping("/internal")
class HealthController(
    private val info: AppBuildInfo,
) {
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> = noStore(mapOf("status" to "UP", "release" to info.releaseId))
}

/**
 * 受付の準備（`/internal/ready`）と、このインスタンスから見える運用モード（`/internal/ops/mode`）。
 *
 * ready は DB 接続とスキーマ版（CONTRACTS §4.3）だけで判定し、**運用モードを見ない**（infra の start は mode=STARTING の間に呼ぶため。
 * STOPPED/UNKNOWN でも DB とスキーマが良ければ 200）。DB が使えなければ 503 で、理由は固定語彙（接続情報・エラーメッセージは返さない）。
 */
@RestController
@RequestMapping("/internal")
class ReadinessController(
    private val schema: SchemaVersionVerifier,
    private val mode: InspectableModeProvider,
) {
    @GetMapping("/ready")
    fun ready(): ResponseEntity<Map<String, Any?>> {
        val required = schema.required.toString()
        val check = schema.check()
        val body: Map<String, Any?> =
            when (check) {
                is SchemaCheck.Satisfied -> {
                    mapOf("status" to "READY", "schemaVersion" to check.current.toString(), "requiredSchemaVersion" to required)
                }

                is SchemaCheck.Insufficient -> {
                    mapOf("status" to "NOT_READY", "reason" to "SCHEMA_INSUFFICIENT", "schemaVersion" to check.current?.toString(), "requiredSchemaVersion" to required)
                }

                SchemaCheck.Unreachable -> {
                    mapOf("status" to "NOT_READY", "reason" to "DB_UNAVAILABLE", "requiredSchemaVersion" to required)
                }

                is SchemaCheck.Failed -> {
                    mapOf("status" to "NOT_READY", "reason" to if (check.reason == SchemaFailure.AUTHENTICATION) "DB_AUTH_FAILED" else "DB_ERROR", "requiredSchemaVersion" to required)
                }
            }
        return noStore(body, if (check is SchemaCheck.Satisfied) 200 else 503)
    }

    /** CONTRACTS §3.2: `{mode, desired, seq, cacheAgeMs, source}`。読み取り専用で、呼ぶたびに正本を取り直さない（15 秒キャッシュ）。 */
    @GetMapping("/ops/mode")
    fun opsMode(): ResponseEntity<Map<String, Any?>> {
        val s = mode.snapshot()
        return noStore(
            mapOf(
                "mode" to s.mode.name,
                "desired" to s.desiredMode?.name,
                "seq" to s.seq,
                "cacheAgeMs" to s.cacheAgeMs,
                "source" to s.source,
            ),
        )
    }
}

internal fun <T : Any> noStore(
    body: T,
    status: Int = 200,
): ResponseEntity<T> = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body)
