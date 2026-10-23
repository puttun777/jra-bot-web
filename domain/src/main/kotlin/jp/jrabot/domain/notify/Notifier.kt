package jp.jrabot.domain.notify

import jp.jrabot.domain.tenant.TenantId

/** 通知の宛先の種別。実際の宛先（Webhook の URL）は Secret Manager から実装側が選ぶ（CONTRACTS §2）。 */
enum class NotificationChannel {
    /** 利用者向けの通知（買い目の Signal など）。テナントごと。 */
    SIGNAL,

    /** 運用者向けの異常通知（exit 75、モード UNKNOWN など）。テナントに属さない場合がある。 */
    ERROR,
}

/**
 * 通知の内容。配送（Discord の Webhook・文字数制限での分割・429 のリトライなど）は [Notifier] の実装の責務で、ここには含めない。
 * 本文に秘密（トークン・Webhook の URL）や実データのダンプを入れない。
 */
data class Notification(
    /** [NotificationChannel.SIGNAL] では必須。 */
    val tenant: TenantId?,
    val channel: NotificationChannel,
    val subject: String,
    val body: String,
) {
    init {
        require(channel != NotificationChannel.SIGNAL || tenant != null) { "SIGNAL の通知にはテナントが必要" }
        require(subject.isNotBlank() && subject.length <= 200) { "件名は 1〜200 文字" }
        require(body.isNotBlank()) { "本文が空" }
    }
}

/** 配送の結果。「確実に送っていない」と「送れたか分からない」を区別する（二重通知より取りこぼしを選ぶ。CONTRACTS §9.2）。 */
enum class DeliveryResult {
    DELIVERED,

    /** 確実に送っていない（拒否・接続不能など）。再試行してよい。 */
    NOT_SENT,

    /** 送れたか不明（タイムアウトなど）。**自動で再送しない**（`SEND_UNKNOWN` として記録する）。 */
    UNKNOWN,
}

/**
 * 通知の配送口（実装は `:bot` の Discord 版: T3.x）。
 *
 * - 責務: [Notification] を 1 回配送する。
 * - エラー: 配送の結果は [DeliveryResult] で返し、例外にしない。
 * - 冪等性: **冪等ではない**。二重通知の防止は呼び出し側の claim（[jp.jrabot.domain.signal.SignalWriter.claim]）で行う。
 * - テナント境界: [Notification.tenant] の宛先にだけ送る。
 */
interface Notifier {
    fun send(notification: Notification): DeliveryResult
}
