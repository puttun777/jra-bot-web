package jp.jrabot.mltrain

import jp.jrabot.cloudgcp.ops.OpsModeConfigurationException
import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.cloudgcp.store.GcsBlobStore
import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.ml.store.LocalFileBlobStore
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.SystemClock
import jp.jrabot.mltrain.job.ArtifactStore
import jp.jrabot.mltrain.job.ExitCode
import jp.jrabot.mltrain.job.GateConfig
import jp.jrabot.mltrain.job.JobDeps
import jp.jrabot.mltrain.job.JobLog
import jp.jrabot.mltrain.job.JobOutcome
import jp.jrabot.mltrain.job.MlTrainError
import jp.jrabot.mltrain.job.ModelJson
import jp.jrabot.mltrain.job.RegistryUnavailable
import jp.jrabot.mltrain.job.TrainConfig
import jp.jrabot.mltrain.job.TrainJob
import jp.jrabot.mltrain.job.UsageError
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.jdbc.DbSettings
import jp.jrabot.persistence.repository.TrainingPersistence
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** `train` の引数。 */
data class TrainArgs(
    val tenantId: Long,
    val datasetId: String,
    val configPath: String,
    val gatePath: String?,
)

/**
 * ml-train の CLI（旧 ml/src/ml_train/cli.py の移植。CONTRACTS §5.1・§9.1）:
 *
 *     ml-train train --tenant-id N --dataset-id ID --config train.json [--gate gate.json]
 *     ml-train convert-pkl ...   # 利用者のローカル専用（[jp.jrabot.mltrain.legacy.ConvertPklCommand]）
 *
 * 環境変数: APP_ENV(local|prod)、OPS_MODE_SOURCE（`gs://` / APP_ENV=local のときだけ `file:`）、MODEL_STORE_URI（`gs://` / `file:///`）、
 * MODEL_SIGNING_KEY、MODEL_VERIFY_PUBKEY（任意。署名鍵の公開鍵が含まれることを検査）、DB_URL / DB_USER / DB_PASSWORD、GIT_SHA（任意）。
 * 終了コード: 0 正常（REJECTED の登録・登録済み・非 NORMAL のスキップを含む）／1 失敗／2 引数・設定の誤り／75 一時的な失敗。
 * 標準出力に構造化ログ（JSON 1 行）と最後に `JOB_RESULT`。秘密・成果物の内容は出さない。
 *
 * 順序（旧版と同じ）: 引数 → 設定ファイル → 鍵 → 保存先 → DB の環境変数 → **運用モード（DB に接続する前）** → DB への接続の確認 → 学習。
 * 旧版との違い: OPS_MODE_SOURCE・MODEL_STORE_URI は `gs://` も直接扱う（旧版は `file://` のみ。bot と同じ実装を使う）。スキーマ版は確認しない（mltrain は flyway_schema_history を読めない。旧版と同じ）。
 */
class Launcher(
    private val log: JobLog = JobLog(),
    private val modeProviderFactory: (Map<String, String>) -> ModeProvider = { OpsModeProviders.fromEnvironment(it, SystemClock) },
    private val dataSourceFactory: (DbSettings) -> DataSource = { DataSourceFactory.createLazy(it, "ml-train") },
    private val terminated: AtomicBoolean = AtomicBoolean(false),
) {
    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int =
        try {
            when (args.firstOrNull()) {
                "train" -> {
                    train(parseTrain(args.drop(1)), env)
                }

                "convert-pkl" -> {
                    jp.jrabot.mltrain.legacy
                        .ConvertPklCommand(log)
                        .run(args.drop(1), env)
                }

                else -> {
                    throw UsageError("USAGE", "コマンドは train / convert-pkl")
                }
            }
        } catch (e: MlTrainError) {
            result(e.exitCode, e.reason)
        } catch (e: Exception) {
            // 想定外（実装の不具合）。メッセージは接続情報を含み得るので出さず、型だけを残す
            result(ExitCode.FAILURE, "UNEXPECTED_ERROR", mapOf("error" to e.javaClass.simpleName))
        }

    private fun result(
        code: Int,
        reason: String,
        fields: Map<String, Any?> = emptyMap(),
    ): Int {
        log.log("JOB_RESULT", if (code == 0) "INFO" else "ERROR", linkedMapOf<String, Any?>("exit_code" to code, "reason" to reason) + fields)
        return code
    }

    private fun train(
        a: TrainArgs,
        env: Map<String, String>,
    ): Int {
        if (TenantId.ofOrNull(a.tenantId) == null) throw UsageError("USAGE", "--tenant-id は 1 以上")
        if (!Regex("[A-Za-z0-9_-]{1,64}").matches(a.datasetId)) throw UsageError("USAGE", "--dataset-id の形式が不正")
        val cfg = TrainConfig.fromJson(loadJsonFile(a.configPath, "学習設定"))
        val gate = a.gatePath?.let { GateConfig.fromJson(loadJsonFile(it, "品質ゲート設定")) } ?: GateConfig.unconfigured()
        val appEnv = env["APP_ENV"]?.trim()
        if (appEnv != "local" && appEnv != "prod") throw UsageError("APP_ENV_INVALID", "APP_ENV は local または prod")
        // 設定の検証は DB・保存先に触れる前に済ませる。モードも DB に接続する前に判定する
        val seed = ModelJson.loadSigningSeed(env["MODEL_SIGNING_KEY"])
        val pubkeys = ModelJson.parsePubkeys(env["MODEL_VERIFY_PUBKEY"])
        val own = ModelJson.publicKeyOf(seed)
        if (pubkeys.isNotEmpty() && pubkeys.none { it.contentEquals(own) }) {
            throw UsageError("SIGNING_KEY_NOT_TRUSTED", "MODEL_SIGNING_KEY の公開鍵が MODEL_VERIFY_PUBKEY に無い（bot が検証できない署名になる）")
        }
        val blob = openStore(env["MODEL_STORE_URI"], appEnv)
        val db =
            try {
                DbSettings.fromEnv(env, 2)
            } catch (e: IllegalArgumentException) {
                throw UsageError("DB_ENV_MISSING", e.message ?: "DB の環境変数が不正")
            }
        if (appEnv == "prod" && db.user != "mltrain") throw UsageError("DB_USER_INVALID", "本番の DB_USER は mltrain（最小権限）")
        val mode =
            try {
                modeProviderFactory(env).current()
            } catch (e: OpsModeConfigurationException) {
                throw UsageError("OPS_MODE_SOURCE_INVALID", e.message ?: "OPS_MODE_SOURCE が不正")
            }
        if (mode == OpsMode.UNKNOWN) return result(ExitCode.TEMPFAIL, "MODE_UNKNOWN")
        if (mode != OpsMode.NORMAL) return result(ExitCode.OK, "MODE_${mode.name}")

        val dataSource = dataSourceFactory(db)
        try {
            // 旧版と同じく、学習の前に DB に接続できることを確かめる（接続できない = 75、認証・権限 = 1）。
            // スキーマ版の確認はしない: mltrain は flyway_schema_history を読めない（CONTRACTS §8.2。権限を足すのは本番 DB の変更で本人承認が要る）
            try {
                dataSource.connection.use { c -> if (!c.isValid(10)) throw SQLException("接続が有効でない", "08000") }
            } catch (e: SQLException) {
                val err = classifyDbError(RuntimeException(e))
                return result(err.exitCode, err.reason)
            } catch (e: RuntimeException) {
                val err = classifyDbError(e)
                return result(err.exitCode, err.reason)
            }
            val deps =
                JobDeps(
                    store = ArtifactStore(blob),
                    registry = TrainingPersistence.trainingRegistry(dataSource),
                    signingSeed = seed,
                    verifyPubkeys = pubkeys.ifEmpty { listOf(own) },
                    gitSha = env["GIT_SHA"]?.takeIf { it.isNotBlank() } ?: env["RELEASE_ID"]?.takeIf { it.isNotBlank() } ?: "unknown",
                    shouldTerminate = { terminated.get() },
                    log = log,
                    classifyDbError = ::classifyDbError,
                )
            val out: JobOutcome = TrainJob.run(a.tenantId, a.datasetId, cfg, gate, deps)
            return result(ExitCode.OK, out.reason, mapOf("model_id" to out.modelId, "status" to out.status, "gate" to out.gateVerdict))
        } finally {
            (dataSource as? AutoCloseable)?.close()
        }
    }

    companion object {
        // MySQL のエラー番号: 接続数超過・ロック待ち・デッドロック・接続不能/断（旧版の _TRANSIENT と同じ）
        private val TRANSIENT_CODES = setOf(1040, 1205, 1213, 2002, 2003, 2006, 2013, 2055)

        // 認証・権限・DB なし（再試行しても直らない。旧版の _AUTH と同じ）
        private val AUTH_CODES = setOf(1044, 1045, 1142, 1143, 1049)

        /**
         * DB の例外の分類（理由の語彙は旧版と同じ）: 接続できない・一時的 → [RegistryUnavailable]（`DB_UNAVAILABLE`・75）、
         * 認証・権限 → `DB_AUTH_FAILED`（1）、それ以外 → `DB_ERROR`（1）。主キーの重複は呼び出し側（Repository）が先に区別する。
         */
        fun classifyDbError(e: RuntimeException): MlTrainError {
            val chain = generateSequence<Throwable>(e) { it.cause?.takeIf { c -> c !== it } }.take(10).toList()
            val transient =
                chain.any {
                    it is SQLTransientException ||
                        it is SQLNonTransientConnectionException ||
                        it is SQLRecoverableException ||
                        (it is SQLException && (it.sqlState?.startsWith("08") == true || it.errorCode in TRANSIENT_CODES))
                }
            // 認証・権限のエラー番号を先に見る（HikariCP は 1045 を一時的な例外で包むことがあるので、順序を逆にすると 75 になってしまう）
            val auth = chain.any { it is SQLException && it.errorCode in AUTH_CODES }
            if (auth) return MlTrainError("DB_AUTH_FAILED", "レジストリの操作に失敗(${e.javaClass.simpleName})", cause = e)
            if (transient) return RegistryUnavailable(e)
            return MlTrainError("DB_ERROR", "レジストリの操作に失敗(${e.javaClass.simpleName})", cause = e)
        }

        fun parseTrain(args: List<String>): TrainArgs {
            val values = HashMap<String, String>()
            val allowed = setOf("--tenant-id", "--dataset-id", "--config", "--gate")
            var i = 0
            while (i < args.size) {
                val k = args[i]
                if (k !in allowed) throw UsageError("USAGE", "不明な引数: $k")
                if (i + 1 >= args.size) throw UsageError("USAGE", "$k に値が無い")
                if (values.put(k, args[i + 1]) != null) throw UsageError("USAGE", "$k が重複")
                i += 2
            }
            for (k in listOf("--tenant-id", "--dataset-id", "--config")) if (k !in values) throw UsageError("USAGE", "$k は必須")
            val tenant = values.getValue("--tenant-id").let { TenantId.parseOrNull(it) } ?: throw UsageError("USAGE", "--tenant-id は 1 以上の整数")
            return TrainArgs(tenant.value, values.getValue("--dataset-id"), values.getValue("--config"), values["--gate"])
        }

        fun loadJsonFile(
            path: String,
            what: String,
        ): JsonValue {
            val bytes =
                try {
                    Files.readAllBytes(Path.of(path))
                } catch (_: Exception) {
                    throw UsageError("CONFIG_UNREADABLE", "${what}を読めない")
                }
            if (bytes.size > 1_000_000) throw UsageError("CONFIG_INVALID", "${what}が大きすぎる")
            return try {
                StrictJson.parse(bytes)
            } catch (_: JsonFormatException) {
                throw UsageError("CONFIG_INVALID", "${what}が JSON として不正")
            }
        }

        /** `MODEL_STORE_URI`: `gs://bucket[/prefix]`、または `file:///絶対パス`（既存のディレクトリ）。bare path は APP_ENV=local のときだけ。 */
        fun openStore(
            uri: String?,
            appEnv: String,
        ): ArtifactBlobStore {
            val raw = uri?.trim().orEmpty()
            if (raw.isEmpty()) throw UsageError("MODEL_STORE_URI_MISSING", "MODEL_STORE_URI が未設定")
            if (raw.startsWith("gs://")) {
                return try {
                    GcsBlobStore.open(raw)
                } catch (_: IllegalArgumentException) {
                    throw UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI が gs://<bucket>[/<prefix>] の形式でない")
                }
            }
            val path =
                try {
                    when {
                        raw.startsWith("file:///") -> Path.of(URI.create(raw))
                        raw.startsWith("/") && appEnv == "local" -> Path.of(raw)
                        else -> throw UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI は gs:// または file:///絶対パス")
                    }
                } catch (_: IllegalArgumentException) {
                    throw UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI のパスが不正")
                } catch (_: InvalidPathException) {
                    throw UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI のパスが不正")
                }
            if (!Files.isDirectory(path)) throw UsageError("MODEL_STORE_URI_INVALID", "MODEL_STORE_URI の指すディレクトリが無い")
            return LocalFileBlobStore(path)
        }
    }
}
