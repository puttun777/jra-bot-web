package jp.jrabot.bot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * :bot の最小エントリポイント。業務処理は後続タスクで追加する。
 *
 * T2.4: `:persistence` が starter-jdbc / doma-spring-boot-starter を持ち込むが、DataSource を組み立てるのは T2.8
 * （`DataSourceFactory` + `PersistenceConfiguration`）。それまで DB なしで起動できるよう、クラス参照を持たずに名前で除外する。T2.8 でこの除外を外す。
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.seasar.doma.boot.autoconfigure.DomaAutoConfiguration",
    ],
)
class BotApplication

fun main(args: Array<String>) {
    runApplication<BotApplication>(*args)
}
