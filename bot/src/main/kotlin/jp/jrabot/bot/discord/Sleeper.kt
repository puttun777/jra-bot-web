package jp.jrabot.bot.discord

import java.time.Duration

/** 待機の口（テストで実際に眠らないよう注入する）。中断されたら [InterruptedException]（SIGTERM などによる停止要求）。 */
fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(duration: Duration)

    companion object {
        val SYSTEM = Sleeper { Thread.sleep(it.toMillis()) }
    }
}
