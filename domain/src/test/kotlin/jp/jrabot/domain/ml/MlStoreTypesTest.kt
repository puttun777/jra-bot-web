package jp.jrabot.domain.ml

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MlStoreTypesTest {
    @ParameterizedTest
    @ValueSource(strings = ["01J8Z3Q9T4V5W6X7Y8Z9A0B1C2", "0199c7a0-1b2c-7d3e-8f40-123456789abc", "model_1", "a"])
    fun `保存先のパスに安全な ID は受け入れる`(text: String) {
        assertEquals(text, ModelId.of(text).value)
        assertEquals(text, DatasetId.of(text).value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "..", "../x", "a/b", "a\\b", "a.json", "a b", "a\n", "a%2Fb", "モデル", "a\u0000", "/etc/passwd", "x/../y",
        ],
    )
    fun `パストラバーサルになり得る ID は拒否する`(text: String) {
        assertNull(ModelId.ofOrNull(text))
        assertNull(DatasetId.ofOrNull(text))
        assertFailsWith<IllegalArgumentException> { ModelId.of(text) }
        assertFailsWith<IllegalArgumentException> { DatasetId.of(text) }
    }

    @Test
    fun `ID の長さは 1 から 64`() {
        assertEquals(64, ModelId.of("A".repeat(64)).value.length)
        assertEquals(64, DatasetId.of("A".repeat(64)).value.length)
        assertNull(ModelId.ofOrNull("A".repeat(65)))
        assertNull(DatasetId.ofOrNull("A".repeat(65)))
    }

    @Test
    fun `ModelArtifact は内容で等価になり 外からバイト列を書き換えられない`() {
        val json = """{"model_schema_version":1}""".toByteArray()
        val sig = byteArrayOf(1, 2, 3)
        val artifact = ModelArtifact(ModelId.of("m1"), json, sig)

        json[0] = 'X'.code.toByte()
        sig[0] = 99
        assertEquals('{'.code.toByte(), artifact.json()[0])
        assertEquals(1, artifact.signature()[0])

        artifact.json()[1] = 0
        artifact.signature()[1] = 0
        assertEquals('"'.code.toByte(), artifact.json()[1])
        assertEquals(2, artifact.signature()[1])

        val same = ModelArtifact(ModelId.of("m1"), artifact.json(), artifact.signature())
        assertEquals(artifact, same)
        assertEquals(artifact.hashCode(), same.hashCode())
        assertNotEquals(artifact, ModelArtifact(ModelId.of("m2"), artifact.json(), artifact.signature()))
        assertNotEquals(artifact, ModelArtifact(ModelId.of("m1"), artifact.json(), byteArrayOf(9)))
        assertContentEquals(byteArrayOf(1, 2, 3), artifact.signature())
    }

    @Test
    fun `ModelArtifact は空の JSON と空の署名を拒否する`() {
        assertFailsWith<IllegalArgumentException> { ModelArtifact(ModelId.of("m"), ByteArray(0), byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { ModelArtifact(ModelId.of("m"), byteArrayOf(1), ByteArray(0)) }
    }

    @Test
    fun `ModelArtifact の文字列表現に中身を出さない`() {
        assertEquals("ModelArtifact(ModelId(m), json=1B, sig=1B)", ModelArtifact(ModelId.of("m"), byteArrayOf(7), byteArrayOf(8)).toString())
    }

    @Test
    fun `DatasetDescriptor は sha256 とサイズを検査する`() {
        val sha = "a".repeat(64)
        assertEquals(0, DatasetDescriptor(DatasetId.of("d"), 0, sha).sizeBytes)
        for (bad in listOf("", "A".repeat(64), "a".repeat(63), "g".repeat(64))) {
            assertFailsWith<IllegalArgumentException> { DatasetDescriptor(DatasetId.of("d"), 1, bad) }
        }
        assertFailsWith<IllegalArgumentException> { DatasetDescriptor(DatasetId.of("d"), -1, sha) }
    }

    @Test
    fun `書込みの結果に 上書きしない CONFLICT がある`() {
        assertEquals(listOf("CREATED", "UNCHANGED", "CONFLICT"), ArtifactWriteOutcome.entries.map { it.name })
    }
}
