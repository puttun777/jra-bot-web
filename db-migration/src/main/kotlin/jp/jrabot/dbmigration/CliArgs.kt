package jp.jrabot.dbmigration

enum class Mode(
    val argValue: String,
) {
    INFO("info"),
    VALIDATE("validate"),
    MIGRATE("migrate"),
    ;

    companion object {
        fun fromArg(value: String): Mode? = entries.firstOrNull { it.argValue == value }
    }
}

sealed interface ParsedArgs {
    data class Ok(
        val mode: Mode,
    ) : ParsedArgs

    data class Invalid(
        val message: String,
    ) : ParsedArgs
}

/** `--mode=info|validate|migrate` だけを受け付ける。既定値は無く、未指定・重複・未知の引数はすべて不正（終了コード 2）。 */
object CliArgs {
    const val USAGE = "usage: db-migration --mode=info|validate|migrate"

    private const val PREFIX = "--mode="

    fun parse(args: List<String>): ParsedArgs {
        if (args.isEmpty()) return ParsedArgs.Invalid("--mode は必須です")
        var mode: Mode? = null
        for (arg in args) {
            if (!arg.startsWith(PREFIX)) return ParsedArgs.Invalid("不明な引数です: ${printable(arg)}")
            if (mode != null) return ParsedArgs.Invalid("--mode が複数指定されています")
            val value = arg.removePrefix(PREFIX)
            mode = Mode.fromArg(value) ?: return ParsedArgs.Invalid("--mode の値が不正です: ${printable(value)}")
        }
        return ParsedArgs.Ok(checkNotNull(mode))
    }

    /** 引数をエラーメッセージに出すときの整形（長さを制限し、制御文字を除く）。 */
    private fun printable(text: String): String = "'" + text.take(40).filter { !it.isISOControl() } + "'"
}
