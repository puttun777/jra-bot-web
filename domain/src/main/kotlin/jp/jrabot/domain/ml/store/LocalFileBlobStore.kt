package jp.jrabot.domain.ml.store

import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.ArtifactKeys
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.StorageException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * ローカルのディレクトリ（またはそこにマウントされたバケット）に置く [ArtifactBlobStore]。旧 Python の `LocalBlobStore` の移植。
 * 書込みは一時ファイルに書いて `fsync` し、**ハードリンクで原子的に確定**する（既にあれば作らない）。ハードリンクが使えないファイルシステム
 * （FUSE など）では排他作成（CREATE_NEW）で直接書く。
 */
class LocalFileBlobStore(
    private val root: Path,
) : ArtifactBlobStore {
    override val uriPrefix: String = root.toUri().toString().trimEnd('/')

    private fun path(key: String): Path = ArtifactKeys.validate(key).fold(root) { p, s -> p.resolve(s) }

    override fun get(key: String): ByteArray? {
        val p = path(key)
        return try {
            if (!Files.isRegularFile(p)) null else Files.readAllBytes(p)
        } catch (e: IOException) {
            throw StorageException("読込みに失敗", e)
        }
    }

    override fun putIfAbsent(
        key: String,
        data: ByteArray,
        contentType: String,
    ): ArtifactWriteOutcome {
        val p = path(key)
        try {
            val dir = checkNotNull(p.parent)
            Files.createDirectories(dir)
            if (Files.exists(p)) return compare(p, data)
            val tmp = Files.createTempFile(dir, ".tmp-", "")
            try {
                Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC).use { it.write(data) }
                try {
                    Files.createLink(p, tmp)
                } catch (_: FileAlreadyExistsException) {
                    return compare(p, data)
                } catch (_: UnsupportedOperationException) {
                    return exclusiveCreate(p, data)
                } catch (e: IOException) {
                    if (Files.exists(p)) return compare(p, data)
                    // ハードリンクを作れないファイルシステム: 排他作成で直接書く
                    return exclusiveCreate(p, data, e)
                }
                return ArtifactWriteOutcome.CREATED
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            throw StorageException("書込みに失敗", e)
        }
    }

    private fun exclusiveCreate(
        p: Path,
        data: ByteArray,
        linkError: IOException? = null,
    ): ArtifactWriteOutcome =
        try {
            Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC).use { it.write(data) }
            ArtifactWriteOutcome.CREATED
        } catch (_: FileAlreadyExistsException) {
            compare(p, data)
        } catch (e: IOException) {
            throw StorageException("書込みに失敗", linkError ?: e)
        }

    private fun compare(
        p: Path,
        data: ByteArray,
    ): ArtifactWriteOutcome = if (Files.readAllBytes(p).contentEquals(data)) ArtifactWriteOutcome.UNCHANGED else ArtifactWriteOutcome.CONFLICT

    override fun delete(key: String) {
        try {
            Files.deleteIfExists(path(key))
        } catch (e: IOException) {
            throw StorageException("削除に失敗", e)
        }
    }
}
