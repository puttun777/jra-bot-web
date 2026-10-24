package jp.jrabot.ingest.agent

import jp.jrabot.domain.ingest.SupportedDataSpecs
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.time.Clock
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * 取得計画の元（CONTRACTS §3.2 `GET /v1/agent/plan`）。**公式仕様で確認済みと明示された取得（[verified]）だけ**を配る。
 * 既定は空 = 常に idle（未確認の dataspec/option を動作確認済みのように配らない）。項目は運用者が与えた JSON（本人専用）。
 */
class PlanProvider(
    private val itemsJson: String,
    private val verified: Set<String>,
    private val json: JsonTrees,
    private val clock: Clock,
) {
    fun plan(): Map<String, Any?> {
        val now = clock.now()
        val items = if (itemsJson.isEmpty()) emptyList() else parseItems()
        val validUntil = now.plus(Duration.ofHours(1)).toString()
        val id =
            "p-" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(("$itemsJson|$verified").toByteArray())
                    .take(6)
                    .joinToString("") { "%02x".format(it) }
        return mapOf("planId" to id, "validUntil" to validUntil, "items" to items, "idle" to items.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseItems(): List<Map<String, Any?>> {
        val tree = runCatching { json.parse(itemsJson.toByteArray()) }.getOrNull() as? List<*> ?: return emptyList()
        return tree.filterIsInstance<Map<String, Any?>>().filter { permitted(it) }
    }

    private fun permitted(item: Map<String, Any?>): Boolean {
        val ds = item["dataspec"] as? String ?: return false
        if (ds !in SupportedDataSpecs.stored && ds !in SupportedDataSpecs.realtime) return false
        val option = item["option"]
        val specKey = if (option != null) "$ds:$option" else ds
        return specKey in verified // 未確認の仕様は配らない
    }
}

/**
 * エージェント向けの補助 API。`/mode` は **DB に触れず・認証なし**（ビーコンと同じ公開情報だけ。NORMAL 以外でも応答する。判定の主は署名付きビーコン）。
 * `/plan`・`/update` は認証済み・NORMAL のときだけ（フィルタが先に処理する）。
 */
@RestController
@RequestMapping("/v1/agent")
class AgentMetaController(
    private val mode: InspectableModeProvider,
    private val plans: PlanProvider,
    private val json: JsonTrees,
    private val updateManifestPath: String,
) {
    @GetMapping("/mode")
    fun mode(): ResponseEntity<String> {
        val s = mode.snapshot()
        return ok(json.write(mapOf("mode" to s.mode.name, "seq" to s.seq)))
    }

    @GetMapping("/plan")
    fun plan(): ResponseEntity<String> = ok(json.write(plans.plan()))

    /** 署名済み manifest をそのまま返す（サーバは検証も署名もしない。エージェントが Ed25519 で検証する）。無ければ 204。 */
    @GetMapping("/update")
    fun update(): ResponseEntity<String> {
        if (updateManifestPath.isEmpty()) return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
        val p = Path.of(updateManifestPath)
        if (!Files.isRegularFile(p) || Files.size(p) > 8192) return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
        return ok(Files.readString(p))
    }

    private fun ok(body: String): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body)
}
