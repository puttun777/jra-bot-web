package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.DuplicateModelException
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.ml.registry.NewModelRecord
import jp.jrabot.domain.ml.registry.TrainingRegistry
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** DB に接続できない・一時的な DB の失敗（exit 75）。 */
class RegistryUnavailable(
    cause: Throwable? = null,
) : TransientError("DB_UNAVAILABLE", "DB に接続できない（一時的な失敗）", cause)

class JobDeps(
    val store: ArtifactStore,
    val registry: TrainingRegistry,
    val signingSeed: ByteArray,
    val verifyPubkeys: List<ByteArray>,
    val clock: () -> Instant = Instant::now,
    val gitSha: String = "unknown",
    val shouldTerminate: () -> Boolean = { false },
    val log: JobLog = JobLog(),
    /** DB の例外を Job の失敗に分類する（接続できない・一時的 = [RegistryUnavailable]、それ以外 = 失敗）。 */
    val classifyDbError: (RuntimeException) -> MlTrainError = { MlTrainError("REGISTRY_ERROR", "レジストリの操作に失敗(${it.javaClass.simpleName})", cause = it) },
)

data class JobOutcome(
    val reason: String,
    val modelId: String,
    val status: String,
    val gateVerdict: String? = null,
)

/**
 * 学習 Job の本体（旧 ml/src/ml_train/job.py の移植）: データセット検証 → 学習 → 評価 → 品質ゲート → モデル JSON・署名・成果物保存 → レジストリ登録（CANDIDATE / REJECTED）。
 *
 * 不変条件（試験で確認）:
 * - 検証に失敗したデータセットでは学習も保存も登録もしない。
 * - 署名・sha256 の検証（書き込んだ成果物の読み戻し）に失敗したら登録しない。成果物はこの実行が作ったものだけ消す。
 * - 登録は CANDIDATE / REJECTED だけ（[NewModelRecord] が型の検査で ACTIVE を拒否）。既存行の更新・削除は無い。既存 ACTIVE は変わらない。
 * - model_id は (tenant, データセット, 設定, ゲート設定, 種別, producer) から決定的に決まる。同じ入力の再実行は ALREADY_REGISTERED（冪等）。
 * - SIGTERM は確定（レジストリ登録）の前なら成果物を片付けて中断（exit 75）。登録後は完了させる。
 */
object TrainJob {
    private val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    private val MODEL_METRIC_KEYS =
        listOf("n", "n_positive", "logloss", "brier", "auc", "logloss_race_normalized", "baseline_market_logloss", "calibration_slope", "calibration_intercept")

    private fun check(deps: JobDeps) {
        if (deps.shouldTerminate()) throw Terminated()
    }

    private inline fun <T> db(
        deps: JobDeps,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DuplicateModelException) {
            throw e
        } catch (e: MlTrainError) {
            throw e
        } catch (e: RuntimeException) {
            throw deps.classifyDbError(e)
        }

    fun libs(): JsonObject =
        JsonObject.of(
            "jdk" to JsonString(System.getProperty("java.version") ?: "unknown"),
            "hipparchus" to JsonString(org.hipparchus.linear.MatrixUtils::class.java.`package`?.implementationVersion ?: "unknown"),
        )

    private fun gateJson(r: QualityGate.Result): JsonObject =
        JsonObject.of(
            "verdict" to JsonString(r.verdict),
            "purpose" to JsonString(r.purpose),
            "reasons" to JsonArray(r.reasons.map(::JsonString)),
            "checks" to
                JsonArray(
                    r.checks.map {
                        JsonObject.of(
                            "name" to JsonString(it.name),
                            "status" to JsonString(it.status),
                            "observed" to (it.observed?.let(ModelJson::f) ?: JsonNull),
                            "threshold" to (it.threshold?.let(ModelJson::f) ?: JsonNull),
                            "detail" to JsonString(it.detail),
                        )
                    },
                ),
        )

    private fun datasetSummary(ds: Dataset): JsonObject {
        val q = ds.manifest["quality"] as? JsonObject ?: throw DatasetInvalid("manifest.quality が無い")
        val nTrain = ds.isTrain.count { it }
        return JsonObject.of(
            "dataset_id" to JsonString(ds.datasetId),
            "csv_sha256" to JsonString(ds.csvSha256),
            "manifest_sha256" to JsonString(ds.manifestSha256),
            "rows" to JsonInt(ds.y.size.toLong()),
            "n_train" to JsonInt(nTrain.toLong()),
            "n_valid" to JsonInt((ds.y.size - nTrain).toLong()),
            "feature_schema_hash" to JsonString(ds.schemaHash),
            "range" to (ds.manifest["range"] ?: JsonNull),
            "has_horse_master_ratio" to (q["has_horse_master_ratio"] ?: JsonNull),
            "has_past_runs_ratio" to (q["has_past_runs_ratio"] ?: JsonNull),
            "constant_features" to (q["constant_features"] ?: JsonNull),
            "flags" to (q["flags"] ?: JsonNull),
            "skipped" to (q["skipped"] ?: JsonNull),
        )
    }

    /** 直前の ACTIVE の `metrics_json` の logloss（数値でなければ比較しない = null）。 */
    internal fun activeLogloss(metricsJson: String?): Double? {
        if (metricsJson == null) return null
        val tree =
            try {
                StrictJson.parse(metricsJson)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return when (val v = (tree as? JsonObject)?.get("logloss")) {
            is JsonInt -> v.value.toDouble()
            is JsonFloat -> v.value
            else -> null
        }
    }

    fun run(
        tenantId: Long,
        datasetId: String,
        cfg: TrainConfig,
        gate: GateConfig,
        deps: JobDeps,
    ): JobOutcome {
        val tenant = TenantId.of(tenantId)
        // --- 1. データセット（検証に失敗したら以降を一切しない）
        val blobs = deps.store.readDataset(tenantId, datasetId) ?: throw DatasetInvalid("データセットが見つからない（manifest が無い）")
        val ds = Dataset.load(blobs.second, blobs.first, tenantId, datasetId)
        deps.log.log("dataset_verified", fields = mapOf("rows" to ds.y.size, "n_train" to ds.isTrain.count { it }, "schema" to ds.schemaHash))
        if (ds.kind != cfg.kind) throw UsageError("CONFIG_KIND_MISMATCH", "設定の kind=${cfg.kind} がデータセットの種別 ${ds.kind} と違う")
        Dataset.requireTrainable(ds)
        check(deps)
        val kind = ModelKind.fromJson(ds.kind) ?: throw DatasetInvalid("未知の種別")

        // --- 2. 冪等性: 同じ入力で登録済みならそのまま返す（成果物の整合は確認する）
        val modelId = TrainingRun.deriveModelId(tenantId, ds.kind, ds.csvSha256, cfg, gate)
        val existing = db(deps) { deps.registry.find(tenant, ModelId.of(modelId)) }
        if (existing != null) {
            val stored = deps.store.readModel(tenantId, modelId) ?: throw MlTrainError("REGISTERED_ARTIFACT_MISSING", "登録済みなのに成果物が無い")
            ModelJson.verify(stored.first, stored.second, deps.verifyPubkeys, tenantId, modelId, ds.kind, existing.featureSchemaHash, existing.sha256)
            deps.log.log("already_registered", fields = mapOf("model_id" to modelId, "status" to existing.status.name))
            return JobOutcome("ALREADY_REGISTERED", modelId, existing.status.name)
        }

        // --- 3〜4. 学習・校正・評価・品質ゲート（設定が無ければ fail closed）
        val active = db(deps) { deps.registry.activeMetrics(tenant, kind) }
        val activeLogloss = activeLogloss(active?.metricsJson)
        val out = TrainingRun.run(ds, cfg, gate, activeLogloss) { check(deps) }
        deps.log.log("trained", fields = mapOf("n_train" to out.trainIdx.size, "n_calibration" to out.calibIdx.size, "n_eval" to out.evalIdx.size))
        check(deps)
        val verdict = out.gate
        val status = out.status
        deps.log.log("gate_evaluated", fields = mapOf("verdict" to verdict.verdict, "status" to status, "reasons" to verdict.reasons, "purpose" to gate.purpose))

        // --- 5. モデル JSON・署名・レポート
        val now = deps.clock()
        val createdAt = ISO_SECONDS.format(now)
        val range = ds.manifest["range"] as? JsonObject ?: throw DatasetInvalid("manifest.range が無い")
        val from = (range["from"] as? JsonString)?.value ?: throw DatasetInvalid("manifest.range.from が無い")
        val to = (range["to"] as? JsonString)?.value ?: throw DatasetInvalid("manifest.range.to が無い")
        val nValid = ds.isTrain.count { !it }
        val training =
            JsonObject.of(
                "mode" to JsonString(cfg.mode),
                "dataset_id" to JsonString(datasetId),
                "dataset_sha256" to JsonString(ds.csvSha256),
                "dataset_manifest_sha256" to JsonString(ds.manifestSha256),
                "from" to JsonString(from),
                "to" to JsonString(to),
                "n_train" to JsonInt(out.trainIdx.size.toLong()),
                "n_valid" to JsonInt(nValid.toLong()),
                "n_calibration" to JsonInt(out.calibIdx.size.toLong()),
                "n_eval" to JsonInt(out.evalIdx.size.toLong()),
                "seed" to JsonInt(cfg.seed),
                "l2_c" to ModelJson.f(cfg.l2C),
                "max_iter" to JsonInt(cfg.maxIter.toLong()),
                "tol" to ModelJson.f(cfg.tol),
                "class_weight" to JsonString("none"),
                "recency_halflife_days" to JsonInt(cfg.recencyHalflifeDays.toLong()),
                "calibration_fraction" to ModelJson.f(cfg.calibrationFraction),
                "git_sha" to JsonString(deps.gitSha),
                "libs" to libs(),
                "gate_purpose" to JsonString(gate.purpose),
                "gate_digest" to JsonString(gate.digest()),
                "gate_verdict" to JsonString(verdict.verdict),
                "feature_schema_hash" to JsonString(ds.schemaHash),
            )
        val metrics = out.eval.metrics
        val modelMetrics = LinkedHashMap<String, JsonValue>()
        for (k in MODEL_METRIC_KEYS) modelMetrics[k] = ModelJson.of(metrics[k])
        modelMetrics["gate_verdict"] = JsonString(verdict.verdict)
        val doc =
            ModelJson.build(
                modelId = modelId,
                tenantId = tenantId,
                kind = ds.kind,
                featureSchema = ds.featureSchema,
                weights = out.model.weights,
                bias = out.model.bias,
                mean = out.model.standardizer.mean,
                std = out.model.standardizer.std,
                platt = out.platt,
                marketBlendAlphaDefault = cfg.marketBlendAlphaDefault,
                training = training,
                metrics = JsonObject(modelMetrics),
                createdAt = createdAt,
                producer = TrainingRun.PRODUCER,
            )
        val raw = CanonicalJson.encodeBytes(doc)
        val sig = ModelJson.sign(deps.signingSeed, raw)
        val report =
            JsonObject.of(
                "report_schema_version" to JsonInt(1),
                "model_id" to JsonString(modelId),
                "tenant_id" to JsonInt(tenantId),
                "kind" to JsonString(ds.kind),
                "status" to JsonString(status),
                "created_at" to JsonString(createdAt),
                "config" to cfg.toJson(),
                "gate" to gateJson(verdict),
                "dataset" to datasetSummary(ds),
                "metrics_eval" to ModelJson.of(metrics),
                "metrics_eval_uncalibrated" to ModelJson.of(out.uncalibrated),
                "metrics_train" to ModelJson.of(out.train),
                "unavailable_metrics" to ModelJson.of(out.eval.unavailable),
                "platt" to JsonObject.of("a" to ModelJson.f(out.platt.first), "b" to ModelJson.f(out.platt.second)),
                "active_reference" to
                    JsonObject.of(
                        "model_id" to (active?.modelId?.value?.let(::JsonString) ?: JsonNull),
                        "logloss" to (activeLogloss?.let(ModelJson::f) ?: JsonNull),
                    ),
                "limitations" to
                    JsonArray(
                        listOf(
                            "品質ゲートの本番閾値は未承認。purpose=synthetic_test の結果は本番採用の根拠にならない",
                            "市場ベースラインはデータセットに含まれる行だけでレース内正規化した近似",
                            "実データでの精度は未検証",
                        ).map(::JsonString),
                    ),
                "libs" to libs(),
                "producer" to JsonString(TrainingRun.PRODUCER),
            )
        val reportBytes = CanonicalJson.encodeBytes(report) + "\n".toByteArray()
        val rowSha = (doc["sha256"] as JsonString).value
        // 自分が署名したものを検証できなければ保存も登録もしない
        ModelJson.verify(raw, sig, deps.verifyPubkeys, tenantId, modelId, ds.kind, ds.schemaHash, rowSha)
        check(deps)

        // --- 6. 保存 → 読み戻し検証 → 登録（失敗したらこの実行が作ったものだけ消す）
        val written = Written()
        try {
            deps.store.writeModel(tenantId, modelId, raw, sig, reportBytes, written)
            val stored = deps.store.readModel(tenantId, modelId)
            if (stored == null || !stored.first.contentEquals(raw) || !stored.second.contentEquals(sig)) {
                throw ModelVerificationError("MODEL_STORAGE_ERROR", "書き込んだ成果物を読み戻せない/内容が違う")
            }
            val verified = ModelJson.verify(stored.first, stored.second, deps.verifyPubkeys, tenantId, modelId, ds.kind, ds.schemaHash, rowSha)
            check(deps) // 確定（登録）の前なら中断できる
            val registryMetrics = LinkedHashMap(modelMetrics)
            registryMetrics["gate_reasons"] = JsonArray(verdict.reasons.map(::JsonString))
            registryMetrics["gate_purpose"] = JsonString(gate.purpose)
            val row =
                NewModelRecord(
                    tenantId = tenant,
                    modelId = ModelId.of(modelId),
                    kind = kind,
                    schemaVersion = 1,
                    featureSchemaHash = ds.schemaHash,
                    artifactUri = deps.store.artifactUri(tenantId, modelId),
                    sha256 = verified.sha256,
                    signatureSha256 = verified.signatureSha256,
                    status = if (status == "CANDIDATE") ModelStatus.CANDIDATE else ModelStatus.REJECTED,
                    trainedFrom = LocalDate.parse(from),
                    trainedTo = LocalDate.parse(to),
                    nTrain = out.trainIdx.size.toLong(),
                    metricsJson = CanonicalJson.encode(JsonObject(registryMetrics)),
                    createdAt = now,
                )
            db(deps) { deps.registry.insert(row) }
        } catch (e: Throwable) {
            val failed = deps.store.cleanup(written)
            deps.log.log("cleanup", "WARNING", mapOf("leftovers" to failed.size, "cause" to e.javaClass.simpleName))
            when (e) {
                is DuplicateModelException -> throw MlTrainError("MODEL_ALREADY_REGISTERED", "並行して同じモデルが登録された", cause = e)
                is MlTrainError -> throw e
                is Exception -> throw MlTrainError("TRAIN_JOB_FAILED", "想定外の失敗: ${e.javaClass.simpleName}", cause = e)
                else -> throw e
            }
        }
        deps.log.log("registered", fields = mapOf("model_id" to modelId, "status" to status))
        return JobOutcome(if (status == "CANDIDATE") "CANDIDATE_REGISTERED" else "MODEL_REJECTED", modelId, status, verdict.verdict)
    }
}
