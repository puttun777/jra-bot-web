package jp.jrabot.ingesttest

import jp.jrabot.ingest.AgentProbe
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Stage 3 のエージェント API の代わりの受け皿（ガードの先に到達したかを数える）。アプリのコンポーネントスキャン
 * （`jp.jrabot.ingest` 配下）に拾われないよう、別パッケージに置き、テスト構成の @Bean で登録する。
 */
@RestController
class ProbeController(
    private val probe: AgentProbe,
) {
    @GetMapping("/v1/agent/plan")
    fun plan(): String = "plan".also { probe.reached.incrementAndGet() }

    @PostMapping("/v1/agent/ingest")
    fun ingest(): String = "ingest".also { probe.reached.incrementAndGet() }

    @GetMapping("/v1/agent/mode")
    fun mode(): String = "mode".also { probe.reached.incrementAndGet() }
}
