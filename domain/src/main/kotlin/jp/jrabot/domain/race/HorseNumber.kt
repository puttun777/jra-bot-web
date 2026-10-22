package jp.jrabot.domain.race

/** 馬番（`umaban`）。1〜99（2 桁、00 は不可）。JRA の最大頭数は設計文書に無いので上限は置いていない（T1.17 で確認、D-060）。 */
@JvmInline
value class HorseNumber private constructor(
    val value: Int,
) : Comparable<HorseNumber> {
    override fun compareTo(other: HorseNumber): Int = value.compareTo(other.value)

    override fun toString(): String = "HorseNumber($value)"

    companion object {
        fun ofOrNull(value: Int): HorseNumber? = if (value in 1..99) HorseNumber(value) else null

        fun of(value: Int): HorseNumber = requireNotNull(ofOrNull(value)) { "馬番は 1〜99: $value" }
    }
}
