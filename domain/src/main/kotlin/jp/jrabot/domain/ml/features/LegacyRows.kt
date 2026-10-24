package jp.jrabot.domain.ml.features

import jp.jrabot.domain.decision.PythonCompat
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import java.time.Instant

/**
 * 旧 SQLite の行（文字列のまま）を、型のある [HorseMaster] / [RaceConditions] / [PastRun] に直す。旧 `ml/features.py` の読み方
 * （`_parse_json_list`・`_sum_chaku_table`・`birth_date` の 8 桁・`.strip()`・祖先名の抽出）を再現する。ゴールデン G6 と旧データの取込み用。
 */
object LegacyRows {
    /** 旧 `horses` の行。JSON 列は文字列のまま。 */
    data class HorseRow(
        val kettoNum: String,
        val birthDate: String?,
        val pedigreeJson: String?,
        val styleTendencyJson: String?,
        val chakuByDistanceJson: String?,
        val chakuBySurfaceJson: String?,
        val chakuByConditionJson: String?,
    )

    /** 旧 `races` の条件列。None は null。 */
    data class RaceRow(
        val distanceM: Int?,
        val trackTypeCd: String?,
        val courseKubunCd: String?,
        val tenkoCd: String?,
        val shibaBabaCd: String?,
        val dirtBabaCd: String?,
    )

    fun horse(
        row: HorseRow,
        updatedAt: Instant = Instant.EPOCH,
    ): HorseMaster {
        val style = parseList(row.styleTendencyJson)
        val styleHint = (style.firstOrNull() as? JsonString)?.value?.let(PythonText::strip) ?: ""
        return HorseMaster(
            kettoNum = PythonText.strip(row.kettoNum),
            birthDate = LegacyDates.parseCompact(PythonText.strip(row.birthDate ?: "")),
            styleHint = styleHint,
            distanceTable = chaku(parseList(row.chakuByDistanceJson)),
            surfaceTable = chaku(parseList(row.chakuBySurfaceJson)),
            conditionTable = chaku(parseList(row.chakuByConditionJson)),
            pedigreeNames = pedigreeNames(parseList(row.pedigreeJson)),
            updatedAt = updatedAt,
        )
    }

    fun race(row: RaceRow): RaceConditions =
        RaceConditions(
            raceDate = null,
            postTime = null,
            distanceM = row.distanceM ?: 0,
            trackTypeCd = PythonText.strip(row.trackTypeCd ?: ""),
            courseKubunCd = PythonText.strip(row.courseKubunCd ?: ""),
            tenkoCd = PythonText.strip(row.tenkoCd ?: ""),
            shibaBabaCd = PythonText.strip(row.shibaBabaCd ?: ""),
            dirtBabaCd = PythonText.strip(row.dirtBabaCd ?: ""),
        )

    /** 旧 `_parse_json_list`: 空・不正・リスト以外は空リスト。 */
    internal fun parseList(text: String?): List<JsonValue> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            (StrictJson.parse(text) as? JsonArray)?.items ?: emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /**
     * 旧 `_sum_chaku_table` の入力変換: 各行がリストで 1 要素以上のものを採り、先頭 6 要素を整数にする
     * （整数 → そのまま、小数 → 0 方向への切捨て、前後の空白を除いて全て数字の文字列 → その整数、それ以外 → 0）。
     */
    internal fun chaku(table: List<JsonValue>): ChakuTable =
        ChakuTable(
            table.mapNotNull { row ->
                val cells = (row as? JsonArray)?.items
                if (cells == null || cells.isEmpty()) null else cells.take(6).map(::cell)
            },
        )

    private fun cell(v: JsonValue): Long =
        when (v) {
            is JsonInt -> v.value
            is JsonFloat -> v.value.toLong()
            is JsonBool -> if (v.value) 1L else 0L
            is JsonString -> PythonText.strip(v.value).let { t -> if (t.isNotEmpty() && t.all { it in '0'..'9' }) t.toLongOrNull() ?: 0L else 0L }
            else -> 0L
        }

    /**
     * 旧の祖先名の抽出: 各要素が辞書なら `str(p.get("name") or "").strip()` が空でないものを集め、**要素を見るたびに** 8 件に達したか判定する。
     * 名前が文字列・整数以外（真偽値・小数・入れ子）のものは旧実装の `str()` の結果を再現できないので、その要素は**対象外にせず例外**にする
     * （黙って違う名前にしない）。
     */
    internal fun pedigreeNames(items: List<JsonValue>): List<String> {
        val names = ArrayList<String>()
        for (p in items) {
            if (p is JsonObject) {
                val nm =
                    when (val v = p["name"]) {
                        null, is jp.jrabot.domain.ml.json.JsonNull -> ""
                        is JsonString -> PythonText.strip(v.value)
                        is JsonInt -> if (v.value == 0L) "" else v.value.toString()
                        is JsonFloat -> if (v.value == 0.0) "" else PythonText.strip(PythonCompat.repr(v.value))
                        else -> throw UnsupportedLegacyValueException("pedigree.name の型は旧 str() の再現対象外")
                    }
                if (nm.isNotEmpty()) names += nm
            }
            if (names.size >= PedigreeHash.MAX_NAMES) break
        }
        return names
    }
}

class UnsupportedLegacyValueException(
    message: String,
) : IllegalArgumentException(message)
