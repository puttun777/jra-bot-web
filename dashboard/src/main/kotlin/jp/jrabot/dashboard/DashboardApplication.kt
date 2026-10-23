package jp.jrabot.dashboard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * :dashboard の起動点（T2.8）。画面・`/api/v1`・利用者の認証/認可・管理者操作は Stage 4。ここにあるのは起動設定・
 * 内部 API（`/internal/health|ready|ops/mode`、IAP JWT による ops サービスアカウント認証）・書込みの運用モードガードだけ。
 *
 * T2.4 で入れていた `DataSourceAutoConfiguration` / `DomaAutoConfiguration` の除外は外した。DataSource は
 * [DashboardConfiguration] が（遅延で）定義するので、Boot の自動構成は引き下がり、起動時に DB へ接続しない。
 */
@SpringBootApplication
class DashboardApplication

fun main(args: Array<String>) {
    runApplication<DashboardApplication>(*args)
}
