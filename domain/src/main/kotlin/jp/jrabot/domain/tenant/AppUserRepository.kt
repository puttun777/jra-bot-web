package jp.jrabot.domain.tenant

/**
 * 人の利用者の読取り（共有表 `app_user`）。現時点では読取りだけ（利用者の変更はインフラ設定・管理手順。書込みは必要になった段階で足す）。
 *
 * **テナントをまたぐ全件取得は提供しない**。[findByEmail] は、認証直後に「この利用者はどのテナントか」を解決するための
 * 唯一の例外で、メールアドレス 1 件の完全一致だけを返す（一覧は返さない）。以降の処理は、得られた [AppUser.tenantId] で限定する。
 */
interface AppUserRepository {
    /** 見つからなければ null（呼び出し側は 403、deny by default）。無効な行（[AppUser.enabled] が false）も返し、判断は呼び出し側。 */
    fun findByEmail(email: EmailAddress): AppUser?

    fun listByTenant(tenantId: TenantId): List<AppUser>
}
