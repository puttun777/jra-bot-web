package jp.jrabot.bot

import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ExpectedModel
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.model.ModelLoadResult
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.model.ModelTrust
import jp.jrabot.domain.ml.store.LocalFileModelStore
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TestDb
import jp.jrabot.persistence.integration.assertDenied
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Kotlin → Python → Kotlin の結合**（T3.6-B）。合成の馬・レース・過去走・オッズ・結果を MySQL に入れ、
 * ① Kotlin の `export-dataset`（本物の Job: モード → Spring → スキーマ版 → DB → ローカル保管）で gzip CSV + manifest を作り、
 * ② Python の `ml-train`（本物の CLI。DB ユーザーは本番と同じ `mltrain`）がデータセットを検証して学習し、署名つきモデルと `ml_model` の CANDIDATE / REJECTED を作り、
 * ③ Kotlin の `ModelLoader` がそのモデルを署名・sha256・スキーマ・次元込みで読めることを確かめる。ACTIVE への昇格は起きない。
 * 旧 pkl 変換ツールの出力（合成の旧モデル）も Kotlin が読めることを確かめる。実データ・実 pkl・本番鍵・実 GCP は使わない（署名鍵は実行時に生成）。
 * 環境変数 `ML_TRAIN_PYTHON`（`ml/requirements.txt` を入れた Python 3.12+ の実行ファイル）が必要。無ければ**失敗**する（スキップしない）。
 */
@Tag("integration")
class MlTrainInteropIntegrationTest {
    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val work: Path = Files.createTempDirectory("ml-interop")
        private val storeDir: Path = Files.createDirectories(work.resolve("store"))
        private val t1 = TenantId.of(1)
        private val mlDir = File("../ml").canonicalFile
        private val repoRoot = File("..").canonicalFile
        private val python: String = System.getenv("ML_TRAIN_PYTHON")?.takeIf { it.isNotBlank() } ?: fail("ML_TRAIN_PYTHON が未設定（ml/requirements.txt を入れた Python の実行ファイル）")
        private val seed: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) } // 実行時に生成するテスト鍵（秘密でも本番鍵でもない）
        private val seedB64: String = Base64.getEncoder().encodeToString(seed)
        private val ops: String = writeOpsState(Files.createTempDirectory(work, "ops")).toString()

        @JvmStatic
        @AfterAll
        fun close() {
            work.toFile().deleteRecursively()
        }

        // ---------------------------------------------------------------- Python の起動
        class PyResult(
            val exit: Int,
            val out: String,
        ) {
            val result: String get() = out.lines().last { "JOB_RESULT" in it }
            val reason: String get() = Regex("\"reason\": \"([A-Z_]+)\"").find(result)!!.groupValues[1]
            val modelId: String get() = Regex("\"model_id\": \"([A-Za-z0-9_-]+)\"").find(result)!!.groupValues[1]
        }

        fun py(
            vararg args: String,
            env: Map<String, String> = emptyMap(),
        ): PyResult {
            val pb = ProcessBuilder(python, *args).directory(mlDir).redirectErrorStream(true)
            pb.environment().apply {
                clear()
                put("PATH", System.getenv("PATH") ?: "")
                put("PYTHONPATH", listOf("$mlDir/src", repoRoot.path, mlDir.path).joinToString(File.pathSeparator))
                put("PYTHONDONTWRITEBYTECODE", "1")
                put("LC_ALL", "C.UTF-8")
                putAll(env)
            }
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                fail("Python が 300 秒で終わらない")
            }
            return PyResult(p.exitValue(), out)
        }

        fun trainEnv(storeUri: String = "file://$storeDir") =
            mapOf(
                "APP_ENV" to "local",
                "OPS_MODE_SOURCE" to "file://$ops",
                "MODEL_STORE_URI" to storeUri,
                "MODEL_SIGNING_KEY" to seedB64,
                "DB_URL" to MySqlSupport.jdbcUrl(db.name),
                "DB_USER" to "mltrain",
                "DB_PASSWORD" to MySqlSupport.PASSWORDS.getValue("mltrain"),
                "GIT_SHA" to "interop-test",
            )

        val publicKeyB64: String by lazy {
            val r = py("-c", "import base64,sys;from ml_train import modeljson as m;print(base64.b64encode(m.public_key_of(base64.b64decode(sys.argv[1]))).decode())", seedB64)
            check(r.exit == 0) { "公開鍵を導出できない" }
            r.out.trim().lines().last()
        }

        // ---------------------------------------------------------------- 合成データ（DB）
        private val strengths = doubleArrayOf(-1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0)
        private val horses = (1..8).map { "2020100%03d".format(it) }

        init {
            seedWorld(db.root())
        }

        private fun seedWorld(c: Connection) =
            c.use {
                it.autoCommit = false
                val rnd = Random(20261024)
                val now = "2026-02-01 00:00:00.000000"
                for (h in horses.indices) {
                    it.createStatement().use { s ->
                        s.execute(
                            "INSERT INTO horses (tenant_id, ketto_num, birth_date, pedigree_json, created_at, updated_at) VALUES (1, '${horses[h]}', '2022-04-01', " +
                                "'[{\"name\":\"sire${h % 3}\"},{\"name\":\"dam$h\"}]', '$now', '2025-01-01 00:00:00.000000')",
                        )
                    }
                }
                val race = it.prepareStatement("INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, post_time, post_time_unknown, distance_m, track_type_cd, course_kubun_cd, tenko_cd, shiba_baba_cd, dirt_baba_cd, created_at, updated_at) VALUES (1, ?, ?, '05', ?, ?, 0, 1600, '11', '00', '1', '2', '3', '$now', '$now')")
                val rh = it.prepareStatement("INSERT INTO race_horses (tenant_id, race_id, umaban, ketto_num, created_at, updated_at) VALUES (1, ?, ?, ?, '$now', '$now')")
                val odds = it.prepareStatement("INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds, popularity) VALUES (1, ?, 'win', ?, ?, ?, ?)")
                val res = it.prepareStatement("INSERT INTO results (tenant_id, race_id, umaban, finish, created_at, updated_at) VALUES (1, ?, ?, ?, '$now', '$now')")
                val past = it.prepareStatement("INSERT INTO horse_past_runs (tenant_id, ketto_num, race_id, run_date, finish, created_at, updated_at) VALUES (1, ?, ?, ?, ?, '$now', '$now')")
                for (h in horses.indices) for (k in 1..3) {
                    past.setString(1, horses[h]); past.setString(2, "2025%02d%02d0%d11".format(10 + k % 3, 1 + k, k)); past.setDate(3, java.sql.Date.valueOf(LocalDate.of(2025, 10 + k % 3, 1 + k)))
                    past.setInt(4, 1 + (h + k) % 8); past.addBatch()
                }
                past.executeBatch()
                for (d in 0 until 24) {
                    val date = LocalDate.of(2026, 1, 3).plusDays(d.toLong())
                    for (r in 1..5) {
                        val raceId = "%04d%02d%02d05%02d".format(date.year, date.monthValue, date.dayOfMonth, r)
                        val post = LocalTime.of(10 + r, 0)
                        race.setString(1, raceId); race.setDate(2, java.sql.Date.valueOf(date)); race.setInt(3, r); race.setTime(4, java.sql.Time.valueOf(post)); race.addBatch()
                        val perm = (1..8).shuffled(rnd) // umaban → 馬のインデックス
                        val latent = DoubleArray(8) { i -> strengths[perm[i] - 1] + rnd.nextDouble(-0.4, 0.4) }
                        val pTrue = latent.map { exp(1.3 * it) }.let { w -> w.map { x -> x / w.sum() } }
                        var u = rnd.nextDouble()
                        var winner = 7
                        for (i in 0 until 8) { u -= pTrue[i]; if (u <= 0) { winner = i; break } }
                        val pMkt = latent.map { exp(0.9 * it + rnd.nextDouble(-0.3, 0.3)) }.let { w -> w.map { x -> x / w.sum() } }
                        val oddsVal = pMkt.map { max(1.1, round(0.8 / it * 10) / 10) }
                        val rank = oddsVal.indices.sortedBy { i -> oddsVal[i] }
                        val finishOrder = (listOf(winner) + (0 until 8).filter { i -> i != winner }.shuffled(rnd))
                        val postUtc = date.atTime(post).minusHours(9)
                        for (i in 0 until 8) {
                            rh.setString(1, raceId); rh.setInt(2, i + 1); rh.setString(3, horses[perm[i] - 1]); rh.addBatch()
                            for ((minutesBefore, o) in listOf(180 to oddsVal[i], 20 to oddsVal[i], 5 to 99.0)) { // 5 分前は as_of(10 分前)より後 → 使われない
                                odds.setString(1, raceId); odds.setTimestamp(2, java.sql.Timestamp.valueOf(postUtc.minusMinutes(minutesBefore.toLong()))); odds.setInt(3, i + 1)
                                odds.setDouble(4, o); odds.setInt(5, rank.indexOf(i) + 1); odds.addBatch()
                            }
                            res.setString(1, raceId); res.setInt(2, i + 1); res.setInt(3, finishOrder.indexOf(i) + 1); res.addBatch()
                        }
                    }
                }
                for (st in listOf(race, rh, odds, res)) st.executeBatch()
                it.commit()
            }

        private fun exportEnv() =
            baseEnv(ops, "DB_URL" to MySqlSupport.jdbcUrl(db.name), "DB_PASSWORD" to MySqlSupport.PASSWORDS.getValue("bot"), "MODEL_STORE_URI" to "file://$storeDir")

        fun export(
            id: String,
            schema: String = "calibration",
        ): Int =
            BotLauncher().run(
                args("export-dataset", "1", "--dataset-id=$id", "--from=2026-01-01", "--to=2026-01-31", "--feature-schema=$schema"),
                exportEnv(),
            )

        fun writeJson(
            name: String,
            text: String,
        ): String = work.resolve(name).also { Files.writeString(it, text) }.toString()
    }

    private val passGate = """{"purpose":"synthetic_test","checks":{"min_valid_rows":100,"min_valid_positives":10,"max_valid_logloss":3.0}}"""
    private val strictGate = """{"purpose":"synthetic_test","checks":{"max_valid_logloss":0.0001}}"""

    private fun train(
        dataset: String,
        configJson: String,
        gateJson: String?,
        kind: String = "calibration",
    ): PyResult {
        val cfg = writeJson("cfg-${System.nanoTime()}.json", configJson.replace("%KIND%", kind))
        val args = mutableListOf("-m", "ml_train", "train", "--tenant-id", "1", "--dataset-id", dataset, "--config", cfg)
        if (gateJson != null) args += listOf("--gate", writeJson("gate-${System.nanoTime()}.json", gateJson))
        return py(*args.toTypedArray(), env = trainEnv())
    }

    private fun statusOf(modelId: String) = db.scalar("SELECT status FROM ml_model WHERE tenant_id = 1 AND model_id = '$modelId'")

    private fun loadVerified(
        modelId: String,
        kind: ModelKind,
    ): ModelLoadResult {
        val id = ModelId.of(modelId)
        val artifact = assertNotNull(LocalFileModelStore(storeDir).read(t1, id), "成果物が保管されている")
        val (sha, schemaHash) = db.query("SELECT sha256, feature_schema_hash FROM ml_model WHERE tenant_id = 1 AND model_id = '$modelId'") { it.getString(1) to it.getString(2) }.single()
        return ModelLoader(ModelTrust.fromBase64(publicKeyB64)).load(t1, id, artifact, ExpectedModel(sha, schemaHash, kind))
    }

    @Test
    fun `export-dataset から学習、署名、CANDIDATE 登録、Kotlin のロードまでが通り、ACTIVE にはならない`() {
        // 既存の ACTIVE（別の種別）が学習の前後で変わらないことを見るため、先に置く
        val hex = { n: Int -> n.toString(16).padStart(64, '0') }
        db.exec(
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, active_flag, created_at, activated_at, activated_by) " +
                "VALUES (1, 'active_alpha_1', 'alpha', 1, 'sha256:${hex(1)}', 'gs://synthetic/a', '${hex(2)}', '${hex(3)}', 'ACTIVE', 1, '2026-01-01 00:00:00.000000', '2026-01-02 00:00:00.000000', 'synthetic-admin')",
        )
        val activeBefore = db.query("SELECT * FROM ml_model WHERE model_id = 'active_alpha_1'") { rs -> (1..rs.metaData.columnCount).map { rs.getString(it) } }

        assertEquals(0, export("it-cal-1"), "export-dataset が成功する")
        assertEquals(0, export("it-cal-1"), "再実行は冪等（同じ内容）")
        val manifest = storeDir.resolve("1/datasets/it-cal-1.manifest.json")
        assertTrue(Files.isRegularFile(manifest) && Files.isRegularFile(storeDir.resolve("1/datasets/it-cal-1.csv.gz")))
        val csv = GZIPInputStream(Files.newInputStream(storeDir.resolve("1/datasets/it-cal-1.csv.gz"))).readAllBytes().toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        val header = csv[0].split(',')
        val oddsCol = header.indexOf("odds")
        assertEquals(960, csv.size - 1, "24 日 × 5 レース × 8 頭")
        assertTrue(csv.drop(1).none { it.split(',')[oddsCol] == "99.0" }, "as_of より後のオッズが特徴量に混入していない")
        assertEquals(120, csv.drop(1).count { it.split(',')[header.indexOf("label")] == "1" }, "各レース（24 日 × 5）に勝ち馬がちょうど 1 頭")

        val cfg = """{"kind":"%KIND%","seed":42,"l2_c":1.0,"recency_halflife_days":90,"calibration_fraction":0.5}"""
        val ok = train("it-cal-1", cfg, passGate)
        assertEquals(0, ok.exit, ok.out)
        assertEquals("CANDIDATE_REGISTERED", ok.reason)
        assertEquals("CANDIDATE", statusOf(ok.modelId))
        val loaded = loadVerified(ok.modelId, ModelKind.CALIBRATION)
        assertIs<ModelLoadResult.Loaded>(loaded, "Python が作った署名つきモデルを Kotlin が読める: $loaded")
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM ml_model WHERE kind = 'calibration' AND (status = 'ACTIVE' OR active_flag IS NOT NULL)"), "自動昇格しない")

        // 再実行は冪等（新しい行を作らない）
        val again = train("it-cal-1", cfg, passGate)
        assertEquals(0, again.exit, again.out)
        assertEquals("ALREADY_REGISTERED", again.reason)
        assertEquals(ok.modelId, again.modelId)
        assertEquals("1", db.scalar("SELECT COUNT(*) FROM ml_model WHERE kind = 'calibration'"))

        // 品質ゲート不合格 → REJECTED（成果物は残る。ACTIVE にしない）
        val rej = train("it-cal-1", cfg, strictGate)
        assertEquals(0, rej.exit, rej.out)
        assertEquals("MODEL_REJECTED", rej.reason)
        assertEquals("REJECTED", statusOf(rej.modelId))
        assertIs<ModelLoadResult.Loaded>(loadVerified(rej.modelId, ModelKind.CALIBRATION))
        // 品質ゲート未設定 → fail closed（REJECTED）
        val none = train("it-cal-1", """{"kind":"%KIND%","seed":7}""", null)
        assertEquals("MODEL_REJECTED", none.reason)
        assertEquals("REJECTED", statusOf(none.modelId))

        // 既存 ACTIVE は変わらず、ml-train の DB ユーザーは昇格・更新・削除できない
        assertEquals(activeBefore, db.query("SELECT * FROM ml_model WHERE model_id = 'active_alpha_1'") { rs -> (1..rs.metaData.columnCount).map { rs.getString(it) } })
        assertEquals("1", db.scalar("SELECT COUNT(*) FROM ml_model WHERE status = 'ACTIVE'"))
        mltrainConnection().use { c ->
            c.createStatement().use { s ->
                assertDenied { s.executeUpdate("UPDATE ml_model SET status = 'ACTIVE', active_flag = 1, activated_at = NOW(6) WHERE model_id = '${ok.modelId}'") }
                assertDenied { s.executeUpdate("DELETE FROM ml_model WHERE model_id = 'active_alpha_1'") }
                assertDenied { s.executeUpdate("INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202601049911', '2026-01-04', '05', 11, NOW(6), NOW(6))") }
            }
        }
    }

    private fun mltrainConnection(): Connection =
        java.sql.DriverManager.getConnection(MySqlSupport.jdbcUrl(db.name), "mltrain", MySqlSupport.PASSWORDS.getValue("mltrain"))

    @Test
    fun `alpha のデータセットも学習でき、市場ベースラインを要求するゲートは REJECTED になる（架空の値を作らない）`() {
        assertEquals(0, export("it-alpha-1", "alpha"))
        val cfg = """{"kind":"%KIND%","seed":42}"""
        val ok = train("it-alpha-1", cfg, passGate, kind = "alpha")
        assertEquals(0, ok.exit, ok.out)
        assertEquals("CANDIDATE_REGISTERED", ok.reason)
        assertIs<ModelLoadResult.Loaded>(loadVerified(ok.modelId, ModelKind.ALPHA))
        val needsBaseline = train("it-alpha-1", cfg, """{"purpose":"synthetic_test","checks":{"max_logloss_minus_market":0.0}}""", kind = "alpha")
        assertEquals("MODEL_REJECTED", needsBaseline.reason)
    }

    @Test
    fun `不正なデータセット・テナント越境・運用モードでは学習も登録もしない`() {
        assertEquals(0, export("it-bad-1"))
        // CSV を 1 バイト改ざん（manifest はそのまま）→ 学習しない
        val csvPath = storeDir.resolve("1/datasets/it-bad-1.csv.gz")
        val original = Files.readAllBytes(csvPath)
        val before = db.scalar("SELECT COUNT(*) FROM ml_model")
        val tampered = GZIPInputStream(original.inputStream()).readAllBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        Files.write(csvPath, java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(tampered) } }.toByteArray())
        val r = train("it-bad-1", """{"kind":"%KIND%"}""", passGate)
        assertEquals(1, r.exit, r.out)
        assertEquals("DATASET_INVALID", r.reason)
        Files.write(csvPath, original)
        // 別テナントとして実行（テナント 2 にそのデータセットは無い）
        val cfg = writeJson("cfg-t2.json", """{"kind":"calibration"}""")
        val t2 = py("-m", "ml_train", "train", "--tenant-id", "2", "--dataset-id", "it-bad-1", "--config", cfg, "--gate", writeJson("g2.json", passGate), env = trainEnv())
        assertEquals(1, t2.exit, t2.out)
        // STOPPED は DB に触れず exit 0
        val stopped = writeOpsState(Files.createTempDirectory(work, "ops-stopped"), "STOPPED").toString()
        val s = py("-m", "ml_train", "train", "--tenant-id", "1", "--dataset-id", "it-bad-1", "--config", cfg, env = trainEnv() + ("OPS_MODE_SOURCE" to "file://$stopped"))
        assertEquals(0, s.exit, s.out)
        assertEquals("MODE_STOPPED", s.reason)
        assertEquals(before, db.scalar("SELECT COUNT(*) FROM ml_model"), "不正・越境・STOPPED では行が増えない")
    }

    @Test
    fun `DB に登録できなければ成果物を片付けて 75 または 1 で終わる（mltrain の権限が無い場合）`() {
        assertEquals(0, export("it-perm-1"))
        db.exec("REVOKE INSERT ON `${db.name}`.`ml_model` FROM 'mltrain'@'%'")
        try {
            val r = train("it-perm-1", """{"kind":"%KIND%","seed":99}""", passGate)
            assertEquals(1, r.exit, r.out) // 権限エラーは再試行しても直らない → 1
            assertEquals("DB_AUTH_FAILED", r.reason)
            assertEquals(emptyList(), storeDir.resolve("1/models").toFile().listFiles()?.filter { it.name.contains("seed99") } ?: emptyList())
        } finally {
            db.exec("GRANT INSERT ON `${db.name}`.`ml_model` TO 'mltrain'@'%'")
        }
        val models = storeDir.resolve("1/models").toFile().listFiles()?.map { it.name }.orEmpty()
        val registered = db.query("SELECT model_id FROM ml_model") { it.getString(1) }.toSet()
        assertTrue(models.all { f -> registered.any { f.startsWith(it) } }, "登録されていない成果物が残っている: $models / $registered")
    }

    @Test
    fun `旧 pkl 変換ツールの出力（合成の旧モデル 5・35・40 次元）を Kotlin が読める`() {
        val schemaDir = File(repoRoot, "domain/src/test/resources/ml").path
        for ((kind, dims, modelKind) in listOf(Triple("market5", 5, ModelKind.LEGACY_COMPAT), Triple("alpha35", 35, ModelKind.ALPHA), Triple("calibration40", 40, ModelKind.LEGACY_COMPAT))) {
            val pkl = work.resolve("old-$kind.pkl").toString()
            assertEquals(0, py("-m", "tests.make_legacy_pkl", pkl, kind).exit)
            val out = work.resolve("conv-$kind")
            val id = "legacy-it-$kind"
            val r =
                py(
                    "-m", "ml_train", "convert-pkl", "--pkl", pkl, "--out-dir", out.toString(), "--tenant-id", "1", "--model-id", id, "--schema-dir", schemaDir, "--confirm-own-local-file",
                    env = mapOf("MODEL_SIGNING_KEY" to seedB64),
                )
            assertEquals(0, r.exit, r.out)
            val artifact = ModelArtifact(ModelId.of(id), Files.readAllBytes(out.resolve("$id.json")), Files.readAllBytes(out.resolve("$id.sig")))
            val loaded = ModelLoader(ModelTrust.fromBase64(publicKeyB64)).load(t1, ModelId.of(id), artifact, ExpectedModel(kind = modelKind))
            assertIs<ModelLoadResult.Loaded>(loaded, "$kind ($dims 次元): $loaded")
        }
        // 次元不一致（feature_names は 40、重みは 35）は変換されない
        val bad = work.resolve("old-bad.pkl").toString()
        assertEquals(0, py("-m", "tests.make_legacy_pkl", bad, "calibration40", "--dim", "35").exit)
        val r = py("-m", "ml_train", "convert-pkl", "--pkl", bad, "--out-dir", work.resolve("conv-bad").toString(), "--tenant-id", "1", "--model-id", "legacy-it-bad", "--schema-dir", schemaDir, "--confirm-own-local-file", env = mapOf("MODEL_SIGNING_KEY" to seedB64))
        assertEquals(1, r.exit, r.out)
        assertFalse(work.resolve("conv-bad").toFile().exists())
    }
}
