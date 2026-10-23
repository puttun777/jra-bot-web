package jp.jrabot.domain.ops

/**
 * 実行環境（環境変数 `APP_ENV`、CONTRACTS §2）。`local` と `prod` だけ。未設定・他の値は null（呼び出し側が設定不正として扱う）。
 *
 * ローカル専用の挙動（モードのローカルファイル、スタブ Job の成功終了）は [LOCAL] のときだけ許す。[PROD] や不明な値で
 * ローカル用の実装に黙って切り替えることはしない。
 */
enum class AppEnv(
    val value: String,
) {
    LOCAL("local"),
    PROD("prod"),
    ;

    companion object {
        fun parseOrNull(raw: String?): AppEnv? = entries.firstOrNull { it.value == raw?.trim() }
    }
}
