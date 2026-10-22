package jp.jrabot.bot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** :bot の最小エントリポイント。業務処理は後続タスクで追加する。 */
@SpringBootApplication
class BotApplication

fun main(args: Array<String>) {
    runApplication<BotApplication>(*args)
}
