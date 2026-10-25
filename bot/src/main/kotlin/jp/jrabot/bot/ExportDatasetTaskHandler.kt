package jp.jrabot.bot

import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.ml.dataset.DatasetExporter
import jp.jrabot.domain.ml.dataset.DatasetPublisher
import jp.jrabot.domain.ml.dataset.DatasetSource
import jp.jrabot.domain.ml.dataset.PublishOutcome
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * `--task=export-dataset`（T3.6-B。ML_DESIGN §11.7）: DB の出走馬・確定着順・オッズ・馬マスタ履歴から、**既存の [DatasetExporter]（PointInTimeFeatureBuilder）**で
 * 学習用データセット（gzip CSV + manifest）を作り、[DatasetStore] に保管する。特徴量は必ずここ（Kotlin）で作る（Python 側で再計算しない）。
 *
 * | 結果 | 終了コード | 理由 |
 * |---|---|---|
 * | 新しく保管した | 0 | `DATASET_CREATED` |
 * | 同じ ID・同じ内容が既にある（再実行） | 0 | `DATASET_UNCHANGED`（冪等） |
 * | 同じ ID で内容が違う | 1 | `DATASET_ID_CONFLICT`（不変なので上書きしない。別の `--dataset-id` を使う） |
 * | 出力できる行が 0 | 1 | `DATASET_EMPTY`（空のデータセットを成功にしない。スキップ理由は manifest ではなくログの件数で確認） |
 * | 保管先の I/O 失敗 | 75 | `STORAGE_ERROR` |
 * 想定外の例外（DB 障害など）は [BotJobExecutor] が 1（`TASK_FAILED`）にする。ログに行の内容・特徴量の値を出さない（件数と理由別のスキップ件数だけ）。
 * 運用モードが NORMAL でなければ、ここに来る前に終了している（DB に接続しない）。
 */
class ExportDatasetTaskHandler(
    private val features: FeatureSource,
    private val source: DatasetSource,
    private val store: DatasetStore,
    private val clock: Clock,
    private val log: Logger = LoggerFactory.getLogger(ExportDatasetTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val args = requireNotNull(command.export) { "export-dataset の引数が無い（BotArgsParser が検証しているはず）" }
        val exported =
            DatasetExporter(features, source).export(
                tenantId = command.tenantId,
                datasetId = args.datasetId,
                schema = args.schema.schema,
                from = args.from,
                to = args.to,
                createdAt = clock.now(),
                validFraction = args.validFraction,
                asOfLead = args.asOfLead,
                producer = "export-dataset/bot",
            )
        val rows = (exported.manifest["rows"] as JsonInt).value
        val quality = exported.manifest["quality"] as JsonObject
        log.info(
            "EXPORT_DATASET_BUILT dataset={} rows={} skipped={} flags={}",
            args.datasetId.value,
            rows,
            (quality["skipped"] as JsonObject).fields.filterValues { it != JsonInt(0) }.mapValues { (it.value as JsonInt).value },
            (quality["flags"] as JsonObject).fields.filterValues { it != JsonInt(0) }.mapValues { (it.value as JsonInt).value },
        )
        if (rows == 0L) return JobResult(JobExit.FAILURE, "DATASET_EMPTY")
        return try {
            when (DatasetPublisher(store).publish(command.tenantId, exported)) {
                PublishOutcome.CREATED -> JobResult(JobExit.OK, "DATASET_CREATED")
                PublishOutcome.UNCHANGED -> JobResult(JobExit.OK, "DATASET_UNCHANGED")
                PublishOutcome.CONFLICT -> JobResult(JobExit.FAILURE, "DATASET_ID_CONFLICT")
            }
        } catch (e: StorageException) {
            log.error("EXPORT_DATASET_STORAGE_ERROR error={}", e.javaClass.simpleName)
            JobResult(JobExit.TEMPFAIL, "STORAGE_ERROR")
        }
    }
}
