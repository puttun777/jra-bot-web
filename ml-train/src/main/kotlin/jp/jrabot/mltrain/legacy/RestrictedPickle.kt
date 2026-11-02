package jp.jrabot.mltrain.legacy

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 読めない・許可しない pickle（理由コードは固定語彙。値・内容をメッセージに含めない）。 */
class UnpickleRefused(
    val reason: String,
    message: String,
) : RuntimeException(message)

/** pickle から取り出した値（**データだけ**。関数・クラスを実行した結果ではない）。 */
sealed interface PValue

data object PNone : PValue

data class PBool(
    val value: Boolean,
) : PValue

data class PInt(
    val value: BigInteger,
) : PValue

data class PFloat(
    val value: Double,
) : PValue

data class PStr(
    val value: String,
) : PValue

class PBytes(
    val value: ByteArray,
) : PValue {
    override fun equals(other: Any?): Boolean = other is PBytes && other.value.contentEquals(value)

    override fun hashCode(): Int = value.contentHashCode()
}

data class PTuple(
    val items: List<PValue>,
) : PValue

/** 変更できる（APPEND）。同一性で比べる（memo の参照を共有する）。 */
class PList(
    val items: MutableList<PValue> = ArrayList(),
) : PValue

class PDict(
    val items: LinkedHashMap<PValue, PValue> = LinkedHashMap(),
) : PValue

class PSet(
    val items: MutableSet<PValue> = LinkedHashSet(),
) : PValue

/** 許可リストのクラス・関数の『名前』（参照だけ。呼び出さない）。 */
data class PGlobal(
    val module: String,
    val name: String,
) : PValue

/** numpy の dtype（`dtype('f8', False, True)` と、その BUILD で付く byte order）。 */
class PDtype(
    val code: String,
) : PValue {
    var byteOrder: Char = '='

    val kind: Char get() = code[0]

    val itemSize: Int get() = code.drop(1).toIntOrNull() ?: 0
}

/** `_reconstruct(ndarray, (0,), b'b')` の結果（BUILD で中身が入るまでの空の配列）。 */
class PArrayShell : PValue {
    var array: PArray? = null
}

/** numpy の配列。数値の dtype なら [data] に値（倍精度に広げたもの）、object 配列なら [objects]。 */
class PArray(
    val dtype: PDtype,
    val shape: List<Long>,
    val data: DoubleArray?,
    val objects: List<PValue>?,
) : PValue

/** numpy のスカラ（`scalar(dtype, bytes)`）。 */
class PScalar(
    val dtype: PDtype,
    val float: Double?,
    val integer: BigInteger?,
) : PValue

/** 旧 `WinProbabilityModel` の空の入れ物（BUILD の状態だけを持つ。メソッドは無い）。 */
class PModel : PValue {
    val state: LinkedHashMap<String, PValue> = LinkedHashMap()
}

/**
 * 旧 `WinProbabilityModel` の pickle を読む、**データだけを解釈する** pickle 読取り器（ML_DESIGN §11.9。旧 Python の許可リスト式 Unpickler の移植）。
 *
 * - **何も実行しない**: Python の Unpickler と違い、関数・クラスを呼び出す仕組みが無い。REDUCE / NEWOBJ / BUILD は、許可リストの
 *   (module, name) について**決まった形だけ**をデータとして組み立てる（numpy の配列・dtype・スカラ、旧クラスの入れ物）。それ以外は拒否。
 * - 許可リスト（旧版と同じ）: 旧クラス（`jraev.ml.model` / `src.jraev.ml.model` の `WinProbabilityModel`）、`numpy.ndarray`・`numpy.dtype`、
 *   `numpy[._]core.multiarray` の `_reconstruct`・`scalar`、`numpy[._]core.numeric` の `_frombuffer`。他の GLOBAL は読んだ時点で拒否（UNKNOWN_CLASS）。
 * - 事前に opcode を走査する（拡張レジストリ `EXT*`・永続 ID `PERSID` は拒否、opcode 数・ファイルの大きさに上限、STOP で終わらなければ MALFORMED）。
 * - 前提は旧版と同じ: 利用者本人が自分の PC で作った自分のファイルを、ローカルで読む（クラウド・他人のファイルには使わない）。
 */
object RestrictedPickle {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_OPCODES = 200_000
    private const val MAX_ITEMS = 1_000_000

    private val LEGACY_MODULES = setOf("jraev.ml.model", "src.jraev.ml.model")
    private const val LEGACY_CLASS = "WinProbabilityModel"
    private val NUMPY_MULTIARRAY = setOf("numpy.core.multiarray", "numpy._core.multiarray")
    private val NUMPY_NUMERIC = setOf("numpy.core.numeric", "numpy._core.numeric")

    private fun allowed(
        module: String,
        name: String,
    ): Boolean =
        (module in LEGACY_MODULES && name == LEGACY_CLASS) ||
            (module in NUMPY_MULTIARRAY && (name == "_reconstruct" || name == "scalar")) ||
            (module in NUMPY_NUMERIC && name == "_frombuffer") ||
            (module == "numpy" && (name == "ndarray" || name == "dtype"))

    private fun isModelClass(g: PValue) = g is PGlobal && g.module in LEGACY_MODULES && g.name == LEGACY_CLASS

    private fun malformed(what: String): Nothing = throw UnpickleRefused("MALFORMED", "読めない: $what")

    /** 走査と解釈。結果が旧クラスの入れ物でなければ NOT_A_MODEL。 */
    fun loadLegacyModel(data: ByteArray): PModel {
        if (data.isEmpty() || data.size > MAX_BYTES) throw UnpickleRefused("SIZE", "ファイルが空、または大きすぎる")
        scan(data)
        val obj = Machine(data).run()
        return obj as? PModel ?: throw UnpickleRefused("NOT_A_MODEL", "WinProbabilityModel ではない")
    }

    // ------------------------------------------------------------------ 走査（旧版の pickletools.genops に相当）

    private class Reader(
        val b: ByteArray,
    ) {
        var pos = 0

        fun u8(): Int = if (pos < b.size) b[pos++].toInt() and 0xff else malformed("途中で終わっている")

        fun take(n: Long): ByteArray {
            if (n < 0 || n > b.size - pos) malformed("長さが不正")
            val out = b.copyOfRange(pos, pos + n.toInt())
            pos += n.toInt()
            return out
        }

        fun le(n: Int): Long {
            val bytes = take(n.toLong())
            var v = 0L
            for (i in n - 1 downTo 0) v = (v shl 8) or (bytes[i].toLong() and 0xff)
            return v
        }

        fun i32(): Int = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).int

        fun line(): String {
            val start = pos
            while (pos < b.size && b[pos] != '\n'.code.toByte()) pos++
            if (pos >= b.size) malformed("行が終わらない")
            val s = String(b, start, pos - start, Charsets.ISO_8859_1)
            pos++
            return s
        }

        fun u64Length(): Long {
            val v = le(8)
            if (v < 0) malformed("長さが不正")
            return v
        }
    }

    /** opcode の引数（解釈に使う形）。 */
    private class Op(
        val code: Int,
        val arg: Any?,
    )

    private fun decode(r: Reader): Op {
        val c = r.u8()
        val arg: Any? =
            when (c.toChar()) {
                '\u0080' -> r.u8() // PROTO
                '\u0095' -> r.le(8) // FRAME（中身は続く opcode）
                '.', '(', '0', '1', '2', 'N', '\u0088', '\u0089', ')', 't', '\u0085', '\u0086', '\u0087', ']', 'l', 'a', 'e', '}', 'd', 's', 'u',
                '\u008f', '\u0090', '\u0091', '\u0093', 'R', 'b', '\u0081', '\u0092', 'o', '\u0094', '\u0097', '\u0098',
                -> null

                'I' -> r.line()
                'J' -> r.i32()
                'K' -> r.u8()
                'M' -> r.le(2).toInt()
                'L' -> r.line()
                '\u008a' -> r.take(r.u8().toLong()) // LONG1
                '\u008b' -> r.take(r.i32().toLong()) // LONG4
                'G' -> ByteBuffer.wrap(r.take(8)).order(ByteOrder.BIG_ENDIAN).double // BINFLOAT
                'F' -> r.line()
                'X' -> r.take(r.le(4)) // BINUNICODE
                '\u008c' -> r.take(r.u8().toLong()) // SHORT_BINUNICODE
                '\u008d' -> r.take(r.u64Length()) // BINUNICODE8
                'V' -> r.line() // UNICODE（raw-unicode-escape）
                'S' -> r.line() // STRING
                'T' -> r.take(r.le(4)) // BINSTRING
                'U' -> r.take(r.u8().toLong()) // SHORT_BINSTRING
                'B' -> r.take(r.le(4)) // BINBYTES
                'C' -> r.take(r.u8().toLong()) // SHORT_BINBYTES
                '\u008e' -> r.take(r.u64Length()) // BINBYTES8
                '\u0096' -> r.take(r.u64Length()) // BYTEARRAY8
                'c' -> r.line() to r.line() // GLOBAL
                'i' -> r.line() to r.line() // INST
                'p' -> r.line() // PUT
                'q' -> r.u8() // BINPUT
                'r' -> r.le(4) // LONG_BINPUT
                'g' -> r.line() // GET
                'h' -> r.u8() // BINGET
                'j' -> r.le(4) // LONG_BINGET
                '\u0082', '\u0083', '\u0084', 'P', 'Q' -> throw UnpickleRefused("FORBIDDEN_OPCODE", "許可しない opcode") // EXT1/2/4・PERSID・BINPERSID
                else -> malformed("未知の opcode")
            }
        return Op(c, arg)
    }

    private fun scan(data: ByteArray) {
        val r = Reader(data)
        var n = 0
        while (true) {
            n++
            if (n > MAX_OPCODES) throw UnpickleRefused("TOO_MANY_OPCODES", "opcode が多すぎる")
            if (decode(r).code == '.'.code) return
        }
    }

    // ------------------------------------------------------------------ 解釈

    private object Mark : PValue

    private class Machine(
        data: ByteArray,
    ) {
        private val r = Reader(data)
        private val stack = ArrayList<PValue>()
        private val marks = ArrayList<Int>()
        private val memo = HashMap<Long, PValue>()

        private fun push(v: PValue) {
            if (stack.size > MAX_ITEMS) malformed("スタックが大きすぎる")
            stack.add(v)
        }

        private fun pop(): PValue = if (stack.isEmpty() || (marks.isNotEmpty() && stack.size <= marks.last())) malformed("スタックが空") else stack.removeAt(stack.size - 1)

        private fun top(): PValue = if (stack.isEmpty()) malformed("スタックが空") else stack.last()

        private fun popMark(): List<PValue> {
            if (marks.isEmpty()) malformed("MARK が無い")
            val at = marks.removeAt(marks.size - 1)
            val items = ArrayList(stack.subList(at, stack.size))
            while (stack.size > at) stack.removeAt(stack.size - 1)
            return items
        }

        private fun str(b: ByteArray): PStr {
            val dec = Charsets.UTF_8.newDecoder()
            return try {
                PStr(dec.decode(ByteBuffer.wrap(b)).toString())
            } catch (_: java.nio.charset.CharacterCodingException) {
                malformed("UTF-8 でない文字列")
            }
        }

        private fun ascii(b: ByteArray): PStr = if (b.all { it >= 0 }) PStr(String(b, Charsets.US_ASCII)) else malformed("ASCII でない文字列")

        private fun parseLong(text: String): PInt =
            try {
                PInt(BigInteger(text.trim().removeSuffix("L")))
            } catch (_: NumberFormatException) {
                malformed("整数が不正")
            }

        private fun leSigned(b: ByteArray): PInt = if (b.isEmpty()) PInt(BigInteger.ZERO) else PInt(BigInteger(b.reversedArray()))

        fun run(): PValue {
            while (true) {
                val op = decode(r)
                when (op.code.toChar()) {
                    '\u0080', '\u0095' -> {}

                    '.' -> {
                        val v = pop()
                        if (v is PArrayShell) malformed("中身の無い配列")
                        return v
                    }

                    '(' -> marks.add(stack.size)
                    '0' -> pop()
                    '1' -> popMark()
                    '2' -> push(top())
                    'N' -> push(PNone)
                    '\u0088' -> push(PBool(true))
                    '\u0089' -> push(PBool(false))
                    'I' -> {
                        val s = op.arg as String
                        push(
                            when (s) {
                                "00" -> PBool(false)
                                "01" -> PBool(true)
                                else -> parseLong(s)
                            },
                        )
                    }

                    'J' -> push(PInt(BigInteger.valueOf((op.arg as Int).toLong())))
                    'K', 'M' -> push(PInt(BigInteger.valueOf((op.arg as Int).toLong())))
                    'L' -> push(parseLong(op.arg as String))
                    '\u008a', '\u008b' -> push(leSigned(op.arg as ByteArray))
                    'G' -> push(PFloat(op.arg as Double))
                    'F' -> push(PFloat((op.arg as String).trim().toDoubleOrNull() ?: malformed("浮動小数が不正")))
                    'X', '\u008c', '\u008d' -> push(str(op.arg as ByteArray))
                    'V' -> malformed("UNICODE（protocol 0）は読まない")
                    'S' -> malformed("STRING（protocol 0）は読まない")
                    'T', 'U' -> push(ascii(op.arg as ByteArray))
                    'B', 'C', '\u008e', '\u0096' -> push(PBytes(op.arg as ByteArray))
                    ')' -> push(PTuple(emptyList()))
                    't' -> push(PTuple(popMark()))
                    '\u0085' -> push(PTuple(listOf(pop())))
                    '\u0086' -> {
                        val b = pop()
                        push(PTuple(listOf(pop(), b)))
                    }

                    '\u0087' -> {
                        val c = pop()
                        val b = pop()
                        push(PTuple(listOf(pop(), b, c)))
                    }

                    ']' -> push(PList())
                    'l' -> push(PList(popMark().toMutableList()))
                    'a' -> {
                        val v = pop()
                        (top() as? PList ?: malformed("APPEND の対象がリストでない")).items.add(v)
                    }

                    'e' -> {
                        val items = popMark()
                        val target = top() as? PList ?: malformed("APPENDS の対象がリストでない")
                        if (target.items.size + items.size > MAX_ITEMS) malformed("リストが大きすぎる")
                        target.items.addAll(items)
                    }

                    '}' -> push(PDict())
                    'd' -> push(PDict().also { putAll(it, popMark()) })
                    's' -> {
                        val v = pop()
                        val k = pop()
                        putAll(top() as? PDict ?: malformed("SETITEM の対象が dict でない"), listOf(k, v))
                    }

                    'u' -> {
                        val items = popMark()
                        putAll(top() as? PDict ?: malformed("SETITEMS の対象が dict でない"), items)
                    }

                    '\u008f' -> push(PSet())
                    '\u0090' -> {
                        val items = popMark()
                        val target = top() as? PSet ?: malformed("ADDITEMS の対象が set でない")
                        for (i in items) target.items.add(hashable(i))
                    }

                    '\u0091' -> push(PSet(popMark().map(::hashable).toMutableSet()))
                    'c' -> {
                        @Suppress("UNCHECKED_CAST")
                        val (m, n) = op.arg as Pair<String, String>
                        push(global(m, n))
                    }

                    '\u0093' -> {
                        val n = pop() as? PStr ?: malformed("STACK_GLOBAL の名前が文字列でない")
                        val m = pop() as? PStr ?: malformed("STACK_GLOBAL のモジュールが文字列でない")
                        push(global(m.value, n.value))
                    }

                    'R' -> {
                        val args = pop() as? PTuple ?: malformed("REDUCE の引数がタプルでない")
                        push(reduce(pop(), args.items))
                    }

                    '\u0081' -> {
                        val args = pop() as? PTuple ?: malformed("NEWOBJ の引数がタプルでない")
                        push(instantiate(pop(), args.items))
                    }

                    '\u0092' -> malformed("NEWOBJ_EX は読まない")
                    'o' -> {
                        val items = popMark()
                        if (items.isEmpty()) malformed("OBJ にクラスが無い")
                        push(instantiate(items[0], items.drop(1)))
                    }

                    'i' -> {
                        @Suppress("UNCHECKED_CAST")
                        val (m, n) = op.arg as Pair<String, String>
                        push(instantiate(global(m, n), popMark()))
                    }

                    'b' -> {
                        val state = pop()
                        build(top(), state)
                    }

                    'p' -> memo[(op.arg as String).trim().toLongOrNull() ?: malformed("PUT の番号")] = top()
                    'q' -> memo[(op.arg as Int).toLong()] = top()
                    'r' -> memo[op.arg as Long] = top()
                    '\u0094' -> memo[memo.size.toLong()] = top()
                    'g' -> push(memo[(op.arg as String).trim().toLongOrNull() ?: malformed("GET の番号")] ?: malformed("memo に無い"))
                    'h' -> push(memo[(op.arg as Int).toLong()] ?: malformed("memo に無い"))
                    'j' -> push(memo[op.arg as Long] ?: malformed("memo に無い"))
                    '\u0097', '\u0098' -> malformed("帯域外のバッファは読まない")
                    else -> malformed("未対応の opcode")
                }
            }
        }

        private fun hashable(v: PValue): PValue =
            when (v) {
                is PNone, is PBool, is PInt, is PFloat, is PStr, is PBytes -> v
                is PTuple -> PTuple(v.items.map(::hashable))
                else -> malformed("ハッシュできないキー")
            }

        private fun putAll(
            d: PDict,
            kv: List<PValue>,
        ) {
            if (kv.size % 2 != 0) malformed("キーと値の数が合わない")
            if (d.items.size + kv.size / 2 > MAX_ITEMS) malformed("dict が大きすぎる")
            for (i in kv.indices step 2) d.items[hashable(kv[i])] = kv[i + 1]
        }

        private fun global(
            module: String,
            name: String,
        ): PGlobal {
            if (!allowed(module, name)) throw UnpickleRefused("UNKNOWN_CLASS", "許可リスト外のクラス/関数")
            return PGlobal(module, name)
        }

        private fun instantiate(
            cls: PValue,
            args: List<PValue>,
        ): PValue {
            if (!isModelClass(cls)) malformed("旧クラス以外のインスタンス化")
            if (args.isNotEmpty()) malformed("旧クラスは引数を取らない")
            return PModel()
        }

        private fun reduce(
            f: PValue,
            args: List<PValue>,
        ): PValue {
            val g = f as? PGlobal ?: malformed("REDUCE の対象が関数でない")
            return when {
                isModelClass(g) -> instantiate(g, args)

                g.module == "numpy" && g.name == "dtype" -> {
                    // dtype(code, align=False, copy=True)
                    val code = (args.getOrNull(0) as? PStr)?.value ?: malformed("dtype の型コード")
                    if (args.size != 3 || args[1] != PBool(false) || args[2] != PBool(true)) malformed("dtype の引数")
                    if (!Regex("[fiubO][0-9]{1,2}").matches(code)) malformed("未対応の dtype")
                    PDtype(code)
                }

                g.name == "_reconstruct" -> {
                    // _reconstruct(ndarray, (0,), b'b') → 空の配列。中身は BUILD で入る
                    val ok = args.size == 3 && args[0] == PGlobal("numpy", "ndarray") && args[1] == PTuple(listOf(PInt(BigInteger.ZERO))) && args[2] is PBytes
                    if (!ok) malformed("_reconstruct の引数")
                    PArrayShell()
                }

                g.name == "_frombuffer" -> {
                    // _frombuffer(buf, dtype, shape, order)
                    if (args.size != 4) malformed("_frombuffer の引数")
                    val buf = args[0] as? PBytes ?: malformed("_frombuffer のバッファ")
                    val dtype = args[1] as? PDtype ?: malformed("_frombuffer の dtype")
                    val order = (args[3] as? PStr)?.value
                    if (order != "C" && order != "F") malformed("_frombuffer の order")
                    numeric(dtype, shape(args[2]), buf.value)
                }

                g.name == "scalar" -> {
                    // scalar(dtype, bytes)
                    if (args.size != 2) malformed("scalar の引数")
                    val dtype = args[0] as? PDtype ?: malformed("scalar の dtype")
                    val raw = args[1] as? PBytes ?: malformed("object のスカラは読まない")
                    val arr = numeric(dtype, listOf(1L), raw.value)
                    when (dtype.kind) {
                        'f' -> PScalar(dtype, checkNotNull(arr.data)[0], null)
                        'i', 'u', 'b' -> PScalar(dtype, null, integerScalar(dtype, raw.value))
                        else -> malformed("未対応のスカラ")
                    }
                }

                else -> malformed("この関数は REDUCE で読まない")
            }
        }

        private fun shape(v: PValue): List<Long> {
            val t = v as? PTuple ?: malformed("shape がタプルでない")
            return t.items.map {
                val n = (it as? PInt)?.value ?: malformed("shape が整数でない")
                if (n.signum() < 0 || n.bitLength() > 31) malformed("shape が不正")
                n.toLong()
            }
        }

        private fun order(dtype: PDtype): ByteOrder =
            when (dtype.byteOrder) {
                '>' -> ByteOrder.BIG_ENDIAN
                '<', '=', '|' -> ByteOrder.LITTLE_ENDIAN // 旧モデルは x86 の Windows で作られた（native = little）
                else -> malformed("byte order")
            }

        private fun integerScalar(
            dtype: PDtype,
            raw: ByteArray,
        ): BigInteger {
            if (raw.size != dtype.itemSize) malformed("スカラの長さ")
            val le = if (order(dtype) == ByteOrder.BIG_ENDIAN) raw.reversedArray() else raw
            val be = le.reversedArray()
            return if (dtype.kind == 'i') BigInteger(be) else BigInteger(1, be)
        }

        /** 数値の配列。浮動小数は倍精度に広げる（Python の `float(x)` と同じ）。整数・真偽は data を作らない（重みとしては不正）。 */
        private fun numeric(
            dtype: PDtype,
            shape: List<Long>,
            raw: ByteArray,
        ): PArray {
            val count = shape.fold(1L) { a, b -> a * b }
            if (dtype.itemSize <= 0 || count * dtype.itemSize != raw.size.toLong()) malformed("配列の長さ")
            if (dtype.kind == 'O') malformed("object 配列のバッファ")
            if (dtype.kind != 'f') return PArray(dtype, shape, null, null)
            val bb = ByteBuffer.wrap(raw).order(order(dtype))
            val values =
                DoubleArray(count.toInt()) {
                    when (dtype.itemSize) {
                        8 -> bb.double
                        4 -> bb.float.toDouble()
                        2 -> java.lang.Float.float16ToFloat(bb.short).toDouble()
                        else -> malformed("未対応の浮動小数の幅")
                    }
                }
            return PArray(dtype, shape, values, null)
        }

        private fun build(
            target: PValue,
            state: PValue,
        ) {
            when (target) {
                is PDtype -> {
                    // (version, byteorder, subarray, names, fields, elsize, alignment, flags)
                    val t = state as? PTuple ?: malformed("dtype の状態")
                    val version = (t.items.getOrNull(0) as? PInt)?.value?.toInt()
                    if (version != 3 && version != 4) malformed("dtype の状態の版")
                    val bo = (t.items.getOrNull(1) as? PStr)?.value
                    if (bo == null || bo.length != 1 || bo[0] !in "<>|=") malformed("dtype の byte order")
                    if (t.items.getOrNull(2) != PNone || t.items.getOrNull(3) != PNone || t.items.getOrNull(4) != PNone) malformed("構造化 dtype は読まない")
                    target.byteOrder = bo[0]
                }

                is PArrayShell -> {
                    // (version, shape, dtype, is_fortran, rawdata | list)
                    if (target.array != null) malformed("配列の状態が 2 回")
                    val t = state as? PTuple ?: malformed("配列の状態")
                    if (t.items.size != 5 || t.items[0] != PInt(BigInteger.ONE)) malformed("配列の状態の形")
                    val shape = shape(t.items[1])
                    val dtype = t.items[2] as? PDtype ?: malformed("配列の dtype")
                    if (t.items[3] !is PBool) malformed("配列の is_fortran")
                    target.array =
                        when (val data = t.items[4]) {
                            is PBytes -> numeric(dtype, shape, data.value)

                            is PList -> {
                                if (dtype.kind != 'O' || data.items.size.toLong() != shape.fold(1L) { a, b -> a * b }) malformed("object 配列の中身")
                                PArray(dtype, shape, null, data.items.toList())
                            }

                            else -> malformed("配列の中身")
                        }
                }

                is PModel -> {
                    val d = state as? PDict ?: malformed("旧クラスの状態が dict でない")
                    for ((k, v) in d.items) target.state[(k as? PStr)?.value ?: malformed("属性名が文字列でない")] = v
                }

                else -> malformed("BUILD の対象")
            }
        }
    }

    /** 解釈が終わった値（配列の入れ物は中身に置き換える）。 */
    fun resolve(v: PValue): PValue = if (v is PArrayShell) v.array ?: malformed("中身の無い配列") else v
}
