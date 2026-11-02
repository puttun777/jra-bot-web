package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.ActiveModelMetrics
import jp.jrabot.domain.ml.registry.DuplicateModelException
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.ml.registry.NewModelRecord
import jp.jrabot.domain.ml.registry.TrainingRegistry
import jp.jrabot.domain.ml.store.LocalFileBlobStore
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.mltrain.Launcher
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** レジストリの試験用実装（旧 InMemoryRegistry）。失敗の注入ができる。 */
class InMemoryTrainingRegistry : TrainingRegistry {
    val rows = LinkedHashMap<Pair<Long, String>, NewModelRecord>()
    val actives = LinkedHashMap<Pair<Long, String>, ActiveModelMetrics>()
    var failInsertWith: RuntimeException? = null
    var failFindWith: RuntimeException? = null

    override fun find(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelRecord? {
        failFindWith?.let { throw it }
        val r = rows[tenantId.value to modelId.value] ?: return null
        return ModelRecord(
            r.tenantId,
            r.modelId,
            r.kind,
            r.schemaVersion,
            r.featureSchemaHash,
            r.artifactUri,
            r.sha256,
            r.signatureSha256,
            r.status,
            createdAt = r.createdAt,
        )
    }

    override fun activeMetrics(
        tenantId: TenantId,
        kind: ModelKind,
    ): ActiveModelMetrics? = actives[tenantId.value to kind.json]

    override fun insert(row: NewModelRecord) {
        failInsertWith?.let { throw it }
        val key = row.tenantId.value to row.modelId.value
        if (key in rows) throw DuplicateModelException("重複")
        rows[key] = row
    }
}

class TrainJobTest {
    companion object {
        val SEED = ByteArray(32) { (it + 10).toByte() }
        val PUB: ByteArray = ModelJson.publicKeyOf(SEED)
        val NOW: Instant = Instant.parse("2026-10-24T01:02:03Z")
        val GOOD_GATE: GateConfig =
            GateConfig.fromJson(
                StrictJson.parse("""{"purpose":"synthetic_test","checks":{"min_valid_rows":50,"min_valid_positives":5,"max_valid_logloss":2.0}}"""),
            )
        val CFG: TrainConfig = TrainConfig.fromJson(StrictJson.parse("""{"kind":"calibration","seed":42,"l2_c":1.0,"recency_halflife_days":90}"""))

        fun cfg(json: String): TrainConfig = TrainConfig.fromJson(StrictJson.parse(json))

        fun gate(json: String): GateConfig = GateConfig.fromJson(StrictJson.parse(json))
    }

    class Env(
        val root: Path = createTempDirectory("ml-train-job"),
        kind: String = "calibration",
        signal: Double = 1.2,
        oneClass: Boolean = false,
        tenantId: Long = 1,
    ) {
        var blob: ArtifactBlobStore = LocalFileBlobStore(root)
        val store get() = ArtifactStore(blob)
        val registry = InMemoryTrainingRegistry()
        var terminate: () -> Boolean = { false }
        val logOut = ByteArrayOutputStream()

        init {
            val (gz, mf) = SynthDataset.make(kind = kind, signal = signal, oneClass = oneClass, tenantId = tenantId)
            putDataset(gz, mf)
        }

        fun putDataset(
            gz: ByteArray,
            mf: ByteArray,
            tenant: Long = 1,
            ds: String = "ds-synth-1",
        ) {
            val b = LocalFileBlobStore(root)
            b.putIfAbsent("$tenant/datasets/$ds.csv.gz", gz, "application/gzip")
            b.putIfAbsent("$tenant/datasets/$ds.manifest.json", mf, "application/json")
        }

        fun deps(
            pubkeys: List<ByteArray> = listOf(PUB),
            clock: () -> Instant = { NOW },
        ): JobDeps =
            JobDeps(
                store = store,
                registry = registry,
                signingSeed = SEED,
                verifyPubkeys = pubkeys,
                clock = clock,
                gitSha = "abc",
                shouldTerminate = { terminate() },
                log = JobLog(PrintStream(logOut, true, Charsets.UTF_8)) { NOW },
            )

        /** datasets 以外のファイル（成果物）。 */
        fun files(): List<String> =
            root
                .walk()
                .filter { it.isRegularFile() && "/datasets/" !in it.toString() }
                .map { it.relativeTo(root).toString() }
                .sorted()
                .toList()

        fun json(key: String): JsonObject = StrictJson.parse(assertNotNull(LocalFileBlobStore(root).get(key))) as JsonObject

        fun run(
            c: TrainConfig = CFG,
            g: GateConfig = GOOD_GATE,
            tenant: Long = 1,
            ds: String = "ds-synth-1",
            d: JobDeps = deps(),
        ): JobOutcome = TrainJob.run(tenant, ds, c, g, d)
    }

    private fun JsonObject.obj(k: String) = this[k] as JsonObject

    private fun JsonObject.str(k: String) = (this[k] as JsonString).value

    @Test
    fun `pass registers a candidate with a verifiable signed model`() {
        val e = Env()
        val out = e.run()
        assertEquals("CANDIDATE_REGISTERED" to "CANDIDATE", out.reason to out.status)
        val row = assertNotNull(e.registry.rows[1L to out.modelId])
        assertEquals(ModelStatus.CANDIDATE, row.status)
        assertEquals(ModelKind.CALIBRATION, row.kind)
        val (raw, sig) = assertNotNull(e.store.readModel(1, out.modelId))
        ModelJson.verify(raw, sig, listOf(PUB), 1, out.modelId, "calibration", row.featureSchemaHash, row.sha256)
        assertEquals(ModelJson.sha256Hex(sig), row.signatureSha256)
        val m = StrictJson.parse(raw) as JsonObject
        val manifest = e.json("1/datasets/ds-synth-1.manifest.json")
        assertEquals(manifest.obj("csv").str("sha256"), m.obj("training").str("dataset_sha256"))
        assertEquals("none", m.obj("training").str("class_weight"))
        assertEquals("standardize", m.obj("preprocessing").str("type"))
        assertEquals("platt", m.obj("calibration").str("type"))
        assertEquals(40, (m["weights"] as JsonArray).items.size)
        assertEquals("PASS", m.obj("metrics").str("gate_verdict"))
        assertEquals("ml-train/1.0.0", m.str("producer"))
        assertTrue(row.artifactUri.startsWith("file:/") && row.artifactUri.endsWith("/1/models/${out.modelId}.json"))
        val report = e.json("1/reports/${out.modelId}.report.json")
        assertEquals("CANDIDATE", report.str("status"))
        assertEquals("PASS", report.obj("gate").str("verdict"))
        assertEquals(
            jp.jrabot.domain.ml.json
                .JsonBool(true),
            report.obj("metrics_eval")["market_baseline_available"],
        )
        // レジストリの metrics_json は正準 JSON（ゲートの理由・目的を含む）
        val metrics = StrictJson.parse(row.metricsJson) as JsonObject
        assertEquals(row.metricsJson, CanonicalJson.encode(metrics))
        assertEquals(JsonArray(emptyList()), metrics["gate_reasons"])
        // 構造化ログの最後は registered。鍵・署名の値はログに出ない
        val log = e.logOut.toString(Charsets.UTF_8)
        assertTrue(log.lines().any { "\"event\":\"registered\"" in it })
        assertTrue(
            java.util.Base64
                .getEncoder()
                .encodeToString(SEED) !in log,
        )
    }

    @Test
    fun `model learns the synthetic signal`() {
        val e = Env(signal = 1.5)
        val out = e.run()
        val m = e.json("1/reports/${out.modelId}.report.json").obj("metrics_eval")
        val auc = (m["auc"] as jp.jrabot.domain.ml.json.JsonFloat).value
        val ll = (m["logloss"] as jp.jrabot.domain.ml.json.JsonFloat).value
        assertTrue(auc > 0.6 && ll < 0.45, "auc=$auc logloss=$ll")
    }

    @Test
    fun `gate reject is registered as rejected with artifacts kept`() {
        val e = Env()
        val out = e.run(g = gate("""{"purpose":"synthetic_test","checks":{"max_valid_logloss":0.01}}"""))
        assertEquals(Triple("MODEL_REJECTED", "REJECTED", "FAIL"), Triple(out.reason, out.status, out.gateVerdict))
        assertEquals(
            ModelStatus.REJECTED,
            e.registry.rows
                .getValue(1L to out.modelId)
                .status,
        )
        assertNotNull(e.store.readModel(1, out.modelId))
        val reasons = (e.json("1/reports/${out.modelId}.report.json").obj("gate")["reasons"] as JsonArray).items
        assertTrue(JsonString("max_valid_logloss:FAIL") in reasons)
    }

    @Test
    fun `missing gate is fail closed`() {
        val e = Env()
        val out = e.run(g = GateConfig.unconfigured())
        assertEquals("REJECTED" to "NOT_CONFIGURED", out.status to out.gateVerdict)
        val metrics =
            StrictJson.parse(
                e.registry.rows
                    .getValue(1L to out.modelId)
                    .metricsJson,
            ) as JsonObject
        assertEquals(JsonArray(listOf(JsonString("QUALITY_GATE_NOT_CONFIGURED"))), metrics["gate_reasons"])
    }

    @Test
    fun `alpha has no market baseline and a baseline check rejects`() {
        val e = Env(kind = "alpha")
        val c = cfg("""{"kind":"alpha"}""")
        val out = e.run(c, gate("""{"purpose":"synthetic_test","checks":{"max_logloss_minus_market":0.0}}"""))
        assertEquals("REJECTED", out.status)
        val rep = e.json("1/reports/${out.modelId}.report.json")
        assertEquals(JsonNull, rep.obj("metrics_eval")["baseline_market_logloss"])
        assertTrue(rep.obj("unavailable_metrics").has("baseline_market_logloss"))
        assertEquals("UNAVAILABLE", ((rep.obj("gate")["checks"] as JsonArray).items[0] as JsonObject).str("status"))
        val ok = e.run(c) // 市場ベースラインを要求しないゲートなら通る
        assertEquals("CANDIDATE", ok.status)
        val raw = assertNotNull(e.store.readModel(1, ok.modelId)).first
        assertEquals(35, ((StrictJson.parse(raw) as JsonObject)["weights"] as JsonArray).items.size)
    }

    @Test
    fun `rerun is idempotent and does not duplicate`() {
        val e = Env()
        val first = e.run()
        val files = e.files()
        val again = e.run(d = e.deps(clock = { Instant.parse("2026-11-01T00:00:00Z") }))
        assertEquals("ALREADY_REGISTERED" to first.modelId, again.reason to again.modelId)
        assertEquals(1, e.registry.rows.size)
        assertEquals(files, e.files())
        val other = e.run(cfg("""{"kind":"calibration","seed":1}"""))
        assertNotEquals(first.modelId, other.modelId)
        assertEquals(2, e.registry.rows.size)
    }

    @Test
    fun `registered row with a tampered artifact is an error`() {
        val e = Env()
        val first = e.run()
        e.root.resolve("1/models/${first.modelId}.json").writeBytes("{}".toByteArray())
        assertFailsWith<MlTrainError> { e.run() }
    }

    @Test
    fun `invalid dataset never trains, stores or registers`() {
        val e = Env()
        val (gz, mf) = SynthDataset.make(datasetId = "ds-bad")
        val bad = StrictJson.parse(mf) as JsonObject
        val rows = (bad["rows"] as JsonInt).value
        e.putDataset(gz, CanonicalJson.encodeBytes(bad.with("rows", JsonInt(rows + 1))) + "\n".toByteArray(), ds = "ds-bad")
        val before = e.files()
        assertFailsWith<DatasetInvalid> { e.run(ds = "ds-bad") }
        assertFailsWith<DatasetInvalid> { e.run(ds = "nope") }
        assertEquals(before, e.files())
        assertTrue(e.registry.rows.isEmpty())
    }

    @Test
    fun `insufficient datasets are rejected explicitly`() {
        val e = Env(oneClass = true)
        assertFailsWith<DatasetInsufficient> { e.run() }
        assertTrue(e.registry.rows.isEmpty())
        assertEquals(emptyList(), e.files())
    }

    @Test
    fun `tenant boundary`() {
        val e = Env(tenantId = 1)
        assertFailsWith<DatasetInvalid> { e.run(tenant = 2) } // テナント 2 の置き場には無い
        val (gz, mf) = SynthDataset.make(tenantId = 1)
        e.putDataset(gz, mf, tenant = 2) // 他テナントのデータを自分の置き場に置かれても manifest の tenant_id で拒否
        val err = assertFailsWith<DatasetInvalid> { e.run(tenant = 2) }
        assertTrue("tenant_id" in (err.message ?: ""))
        assertTrue(e.registry.rows.isEmpty())
    }

    @Test
    fun `kind mismatch is a usage error`() {
        val e = Env()
        val err = assertFailsWith<UsageError> { e.run(cfg("""{"kind":"alpha"}""")) }
        assertEquals(ExitCode.USAGE, err.exitCode)
    }

    @Test
    fun `registry failure cleans up artifacts and keeps active`() {
        val e = Env()
        val active = ActiveModelMetrics(ModelId.of("active-1"), """{"logloss":0.3}""")
        e.registry.actives[1L to "calibration"] = active
        val transient = RuntimeException("connection refused", java.sql.SQLTransientConnectionException("08S01"))
        for ((exc, code) in listOf(transient to ExitCode.TEMPFAIL, IllegalStateException("boom") to ExitCode.FAILURE)) {
            e.registry.failInsertWith = exc
            val d = e.deps()
            val classified =
                JobDeps(d.store, d.registry, d.signingSeed, d.verifyPubkeys, d.clock, d.gitSha, d.shouldTerminate, d.log, { Launcher.classifyDbError(it) })
            val err = assertFailsWith<MlTrainError> { TrainJob.run(1, "ds-synth-1", CFG, GOOD_GATE, classified) }
            assertEquals(code, err.exitCode, exc.toString())
            assertEquals(emptyList(), e.files()) // この実行が作った成果物は消える
            assertTrue(e.registry.rows.isEmpty())
            assertEquals(active, e.registry.actives[1L to "calibration"])
        }
    }

    @Test
    fun `the active model is used as the gate reference and named in the report`() {
        val e = Env()
        e.registry.actives[1L to "calibration"] = ActiveModelMetrics(ModelId.of("active-1"), """{"logloss":0.0001}""")
        val out = e.run(g = gate("""{"purpose":"synthetic_test","checks":{"max_logloss_regression_vs_active":0.0}}"""))
        assertEquals("REJECTED", out.status)
        val ref = e.json("1/reports/${out.modelId}.report.json").obj("active_reference")
        assertEquals("active-1", ref.str("model_id"))
    }

    @Test
    fun `duplicate insert race is reported`() {
        val e = Env()
        e.registry.failInsertWith = DuplicateModelException("重複")
        val err = assertFailsWith<MlTrainError> { e.run() }
        assertEquals("MODEL_ALREADY_REGISTERED", err.reason)
        assertEquals(emptyList(), e.files())
    }

    @Test
    fun `storage failure registers nothing and cleans up what this run created`() {
        val e = Env()
        val real = LocalFileBlobStore(e.root)
        e.blob =
            object : ArtifactBlobStore by real {
                override fun putIfAbsent(
                    key: String,
                    data: ByteArray,
                    contentType: String,
                ): ArtifactWriteOutcome {
                    // 署名とレポートは書けたが、確定の印（JSON）の書込みで失敗
                    if (key.endsWith(".json") && "/models/" in key) throw StorageException("simulated")
                    return real.putIfAbsent(key, data, contentType)
                }
            }
        val err = assertFailsWith<MlTrainError> { e.run() }
        assertEquals(ExitCode.TEMPFAIL, err.exitCode)
        assertTrue(e.registry.rows.isEmpty())
        assertEquals(emptyList(), e.files()) // 先に置いた署名・レポートも片付く
    }

    @Test
    fun `an existing conflicting artifact is never overwritten`() {
        val e = Env()
        val modelId = TrainingRun.deriveModelId(1, "calibration", datasetCsvSha(e), CFG, GOOD_GATE)
        val b = LocalFileBlobStore(e.root)
        b.putIfAbsent("1/models/$modelId.sig", ByteArray(64), "application/octet-stream")
        val err = assertFailsWith<ArtifactConflict> { e.run() }
        assertEquals("ARTIFACT_CONFLICT", err.reason)
        assertContentEquals(ByteArray(64), b.get("1/models/$modelId.sig")) // 既存（この実行が作っていない）は消さない
        assertTrue(e.registry.rows.isEmpty())
    }

    private fun datasetCsvSha(e: Env): String = e.json("1/datasets/ds-synth-1.manifest.json").obj("csv").str("sha256")

    @Test
    fun `signature failure blocks registration`() {
        val e = Env()
        val wrong = ModelJson.publicKeyOf(ByteArray(32))
        val err = assertFailsWith<MlTrainError> { e.run(d = e.deps(pubkeys = listOf(wrong))) }
        assertEquals("MODEL_SIGNATURE_INVALID", err.reason)
        assertTrue(e.registry.rows.isEmpty())
        assertEquals(emptyList(), e.files())
    }

    @Test
    fun `tampered store after write blocks registration`() {
        val e = Env()
        val real = LocalFileBlobStore(e.root)
        e.blob =
            object : ArtifactBlobStore by real {
                override fun putIfAbsent(
                    key: String,
                    data: ByteArray,
                    contentType: String,
                ): ArtifactWriteOutcome {
                    val r = real.putIfAbsent(key, data, contentType)
                    if (key.endsWith(".json") && "/models/" in key) Files.write(e.root.resolve(key), data + " ".toByteArray())
                    return r
                }
            }
        val err = assertFailsWith<MlTrainError> { e.run() }
        assertEquals("MODEL_STORAGE_ERROR", err.reason)
        assertTrue(e.registry.rows.isEmpty())
    }

    @Test
    fun `sigterm before commit aborts cleanly at every checkpoint`() {
        // 学習前〜登録直前のどの確認点で SIGTERM を受けても、登録せず成果物を残さない
        var checkpoints = 0
        run {
            val e = Env()
            e.terminate = {
                checkpoints++
                false
            }
            e.run()
        }
        assertTrue(checkpoints >= 5, "確認点 $checkpoints")
        for (k in 1..checkpoints) {
            val e = Env()
            var n = 0
            e.terminate = { ++n >= k }
            val err = assertFailsWith<Terminated>("k=$k") { e.run() }
            assertEquals(ExitCode.TEMPFAIL, err.exitCode)
            assertTrue(e.registry.rows.isEmpty(), "k=$k")
            assertEquals(emptyList(), e.files(), "k=$k")
        }
    }

    @Test
    fun `new model can never be active`() {
        fun row(status: ModelStatus) =
            NewModelRecord(
                TenantId.of(1),
                ModelId.of("m"),
                ModelKind.CALIBRATION,
                1,
                "sha256:" + "a".repeat(64),
                "u",
                "b".repeat(64),
                "c".repeat(64),
                status,
                null,
                null,
                1,
                "{}",
                NOW,
            )
        for (s in listOf(ModelStatus.ACTIVE, ModelStatus.RETIRED)) assertFailsWith<IllegalArgumentException> { row(s) }
        row(ModelStatus.CANDIDATE)
        row(ModelStatus.REJECTED)
    }

    @Test
    fun `model id is deterministic and depends on the inputs`() {
        val x = "x".repeat(64)
        val a = TrainingRun.deriveModelId(1, "calibration", x, CFG, GOOD_GATE)
        assertEquals(a, TrainingRun.deriveModelId(1, "calibration", x, CFG, GOOD_GATE))
        val all =
            setOf(
                a,
                TrainingRun.deriveModelId(2, "calibration", x, CFG, GOOD_GATE),
                TrainingRun.deriveModelId(1, "calibration", "y".repeat(64), CFG, GOOD_GATE),
                TrainingRun.deriveModelId(1, "calibration", x, CFG, GateConfig.unconfigured()),
                TrainingRun.deriveModelId(1, "calibration", x, CFG, GOOD_GATE, "ml-train/0.1.0"),
            )
        assertEquals(5, all.size)
        assertNotNull(ModelId.ofOrNull(a))
    }

    @Test
    fun `same inputs give byte identical model json`() {
        val raws =
            (0..1).map {
                val e = Env()
                val out = e.run()
                assertNotNull(e.store.readModel(1, out.modelId)).first
            }
        assertContentEquals(raws[0], raws[1])
    }

    @Test
    fun `active logloss reads only numbers`() {
        assertEquals(0.3, TrainJob.activeLogloss("""{"logloss":0.3}"""))
        assertEquals(1.0, TrainJob.activeLogloss("""{"logloss":1}"""))
        assertNull(TrainJob.activeLogloss("""{"logloss":true}"""))
        assertNull(TrainJob.activeLogloss("""{"logloss":"0.3"}"""))
        assertNull(TrainJob.activeLogloss("not json"))
        assertNull(TrainJob.activeLogloss(null))
    }
}
