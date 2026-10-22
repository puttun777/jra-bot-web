package jp.jrabot.ingest

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** :ingest の最小エントリポイント。業務処理は後続タスクで追加する。 */
@SpringBootApplication
class IngestApplication

fun main(args: Array<String>) {
    runApplication<IngestApplication>(*args)
}
