package jp.jrabot.domain.ml.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.StorageException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 学習 Job の成果物の置き場（ローカル / GCS ボリュームのマウント先）。作成のみ・上書きしない・キーの検査。 */
class LocalFileBlobStoreTest {
    private val root = Files.createTempDirectory("blob")
    private val store = LocalFileBlobStore(root)

    @Test
    fun `create only, identical rewrite is unchanged, different content is a conflict and is not overwritten`() {
        assertNull(store.get("1/models/m.json"))
        assertEquals(ArtifactWriteOutcome.CREATED, store.putIfAbsent("1/models/m.json", "a".toByteArray(), "application/json"))
        assertEquals(ArtifactWriteOutcome.UNCHANGED, store.putIfAbsent("1/models/m.json", "a".toByteArray(), "application/json"))
        assertEquals(ArtifactWriteOutcome.CONFLICT, store.putIfAbsent("1/models/m.json", "b".toByteArray(), "application/json"))
        assertContentEquals("a".toByteArray(), store.get("1/models/m.json"))
        // 一時ファイルが残らない
        assertEquals(listOf("m.json"), Files.list(root.resolve("1/models")).map { it.fileName.toString() }.toList())
        store.delete("1/models/m.json")
        store.delete("1/models/m.json") // 無くてもよい
        assertNull(store.get("1/models/m.json"))
    }

    @Test
    fun `keys cannot escape the root`() {
        for (k in listOf("../x", "1/../../x", "/abs", "1//x", "1/./x", "1/x y", "")) {
            assertFailsWith<StorageException>(k) { store.putIfAbsent(k, ByteArray(1), "application/octet-stream") }
            assertFailsWith<StorageException>(k) { store.get(k) }
        }
        assertTrue(Files.list(root).count() == 0L)
    }

    @Test
    fun `the uri prefix is a file uri without a trailing slash`() {
        assertTrue(store.uriPrefix.startsWith("file:/") && !store.uriPrefix.endsWith("/"), store.uriPrefix)
    }
}
