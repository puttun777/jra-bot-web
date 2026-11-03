package jp.jrabot.mltrain

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * ml-train の入口（イメージの ENTRYPOINT）。**最初に SIGTERM の受信を置き換える**: JVM の既定（シャットダウンフックを走らせて即終了）にせず、
 * フラグを立てる。学習 Job は『確定（レジストリ登録）の前』の各段階でフラグを見て、確定前なら、この実行が作った成果物だけを片付けて 75 で終わる。
 * 確定後は完了させる（Cloud Run は猶予の後に SIGKILL）。
 */
fun main(args: Array<String>) {
    val terminated = AtomicBoolean(false)
    try {
        sun.misc.Signal.handle(sun.misc.Signal("TERM")) { terminated.set(true) }
    } catch (_: IllegalArgumentException) {
        // シグナルを置き換えられない環境（Windows のローカル実行など）。既定の動作のまま
    }
    exitProcess(Launcher(terminated = terminated).run(args.toList(), System.getenv()))
}
