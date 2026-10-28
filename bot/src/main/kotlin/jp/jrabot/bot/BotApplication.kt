package jp.jrabot.bot

import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import kotlin.system.exitProcess

/**
 * :bot の起動点（T2.8）。Cloud Run Jobs の単一イメージで `--task=bot-run|calendar-check|reconcile|retention|export-dataset` を切り替える。
 * 実装済みは bot-run（T3.8）・calendar-check（T3.3）・export-dataset。**reconcile・retention は未実装のスタブ**（[StubTaskHandler]。本番では exit 1）。
 *
 * T2.4 で入れていた `DataSourceAutoConfiguration` / `DomaAutoConfiguration` の除外は外した。DataSource は [BotConfiguration] が
 * （遅延で）定義するので、Boot の自動構成は引き下がる。さらに Spring のコンテキストは**モード確認の後**（NORMAL のときだけ）にしか作らない
 * （[BotLauncher]）ので、STOPPED/UNKNOWN などでは自動構成も含めて DB に触れない。
 */
@SpringBootApplication
class BotApplication

fun main(args: Array<String>) {
    exitProcess(BotLauncher().run(args, System.getenv()))
}

/** NORMAL と判定された後に Spring のコンテキストを起動し、スキーマ版の確認とタスクの実行をする。 */
object SpringJobExecutor {
    private val log = LoggerFactory.getLogger(SpringJobExecutor::class.java)

    fun run(runtime: BotRuntime): JobResult {
        log.info("JOB_PHASE spring-context-start task={}", runtime.command.task.cliName)
        return SpringApplicationBuilder(BotApplication::class.java)
            .web(WebApplicationType.NONE)
            .logStartupInfo(false)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { ctx -> ctx.beanFactory.registerSingleton("botRuntime", runtime) })
            .run() // 引数は Spring に渡さない（--task などをプロパティにしない）
            .use { it.getBean(BotJobExecutor::class.java).execute() }
    }
}
