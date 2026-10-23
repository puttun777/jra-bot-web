package jp.jrabot.ingest

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * :ingest の起動点（T2.8）。Windows エージェントの受信 API（`/v1/agent` 配下）の本体は Stage 3。ここにあるのは起動設定・
 * 内部 API（`/internal/health|ready|ops/mode`、Google ID トークン認証）・エージェント向け API の運用モードガードだけ。
 *
 * T2.4 で入れていた `DataSourceAutoConfiguration` / `DomaAutoConfiguration` の除外は外した。DataSource は
 * [IngestConfiguration] が（遅延で）定義するので、Boot の自動構成は引き下がり、起動時に DB へ接続しない。
 */
@SpringBootApplication
class IngestApplication

fun main(args: Array<String>) {
    runApplication<IngestApplication>(*args)
}
