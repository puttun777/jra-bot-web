package jp.jrabot.dashboard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** :dashboard の最小エントリポイント。業務処理は後続タスクで追加する。 */
@SpringBootApplication
class DashboardApplication

fun main(args: Array<String>) {
    runApplication<DashboardApplication>(*args)
}
