package jp.jrabot.dashboardtest

import jp.jrabot.dashboard.AdminProbe
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Stage 4 の管理操作の代わりの受け皿（ガードの先に到達したかを数える）。アプリのコンポーネントスキャン
 * （`jp.jrabot.dashboard` 配下）に拾われないよう、別パッケージに置き、テスト構成の @Bean で登録する。本番のコードに管理操作は無い。
 */
@RestController
class ProbeController(
    private val probe: AdminProbe,
) {
    @PostMapping("/api/v1/admin/probe")
    fun post(): String = "post".also { probe.reached.incrementAndGet() }

    @PutMapping("/api/v1/admin/probe")
    fun put(): String = "put".also { probe.reached.incrementAndGet() }

    @PatchMapping("/api/v1/admin/probe")
    fun patch(): String = "patch".also { probe.reached.incrementAndGet() }

    @DeleteMapping("/api/v1/admin/probe")
    fun delete(): String = "delete".also { probe.reached.incrementAndGet() }

    @PostMapping("/other/write")
    fun otherPost(): String = "other".also { probe.reached.incrementAndGet() }

    @GetMapping("/api/v1/admin/probe")
    fun get(): String = "get".also { probe.reached.incrementAndGet() }
}
