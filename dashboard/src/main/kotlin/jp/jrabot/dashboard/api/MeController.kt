package jp.jrabot.dashboard.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** ログイン中の本人（DTO。Entity は公開しない）。 */
data class MeDto(
    val email: String,
    val role: String,
    val tenantId: Long,
)

@RestController
@RequestMapping("/api/v1")
class MeController {
    @GetMapping("/me")
    fun me(request: HttpServletRequest): ResponseEntity<MeDto> {
        val p = ApiAuthFilter.principalOf(request)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MeDto(p.email.value, p.role.name, p.tenantId.value))
    }
}
