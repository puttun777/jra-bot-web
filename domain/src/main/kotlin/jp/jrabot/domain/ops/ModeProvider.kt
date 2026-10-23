package jp.jrabot.domain.ops

/**
 * 運用モードの取得口（CONTRACTS §5.2。実装は `:cloud-gcp` の GCS 版と、ローカル用のファイル版: T2.7）。
 *
 * - 責務: 現在のモードを返す。キャッシュ（成功時 15 秒）・タイムアウト（3 秒）・`seq` の単調性検査は実装の責務で、ここには含めない。
 * - 入力: なし。モードは運用全体で 1 つ（テナントごとではない）。
 * - 出力: [OpsMode]。
 * - エラー: **例外を投げない**。読めない・検証できないときは [OpsMode.UNKNOWN] を返す。直前の成功値を使い回さない（キャッシュ切れの後は UNKNOWN）。
 * - 冪等性: 読取りのみで副作用なし。
 */
fun interface ModeProvider {
    fun current(): OpsMode
}
