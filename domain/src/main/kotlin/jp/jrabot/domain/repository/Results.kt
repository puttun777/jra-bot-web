package jp.jrabot.domain.repository

/** 自然キーでの挿入の結果。既にあれば**何も変えない**（再実行しても結果が同じ）。 */
enum class InsertOutcome { INSERTED, ALREADY_EXISTS }

/** 自然キーでの upsert の結果。同じ内容を再度書いても [UNCHANGED] になる（再実行しても結果が同じ）。 */
enum class UpsertOutcome { CREATED, UPDATED, UNCHANGED }

/**
 * 楽観ロック付きの更新の結果。更新は「渡した `version` と保存されている `version` が等しいときだけ」成功し、`version` が 1 増える。
 * 競合（他の更新が先に入った）と不在は区別する。呼び出し側は [Conflict] なら読み直して再計算する。
 */
sealed interface UpdateResult<out T> {
    /** 成功。[value] は `version` が進んだ後の状態。 */
    data class Updated<T>(
        val value: T,
    ) : UpdateResult<T>

    data object Conflict : UpdateResult<Nothing>

    data object NotFound : UpdateResult<Nothing>
}
