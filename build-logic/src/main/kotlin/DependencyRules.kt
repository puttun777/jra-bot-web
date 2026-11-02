package jrabot.build

/** モジュール間・外部ライブラリの依存規則（ARCHITECTURE §2、D-045）。純粋関数にしてテスト可能にする。 */
object DependencyRules {
    const val DOMAIN = ":domain"
    const val PERSISTENCE = ":persistence"
    const val DB_MIGRATION = ":db-migration"
    const val CLOUD_GCP = ":cloud-gcp"
    const val BOT = ":bot"
    const val INGEST = ":ingest"
    const val DASHBOARD = ":dashboard"

    /** ゴールデンテストの Kotlin 側ランナー（検証専用。JSON の読み書きを持ち、:domain の計算を実行する。アプリのイメージには含めない）。 */
    const val GOLDEN_RUNNER = ":golden-runner"
    const val ML_TRAIN = ":ml-train"

    val modules = listOf(DOMAIN, PERSISTENCE, DB_MIGRATION, CLOUD_GCP, BOT, INGEST, DASHBOARD, GOLDEN_RUNNER, ML_TRAIN)
    val apps = setOf(BOT, INGEST, DASHBOARD, ML_TRAIN)

    /** 各モジュールが依存してよいプロジェクト。表にないものはすべて禁止。 */
    val allowedProjectDependencies: Map<String, Set<String>> =
        mapOf(
            DOMAIN to emptySet(),
            PERSISTENCE to setOf(DOMAIN),
            DB_MIGRATION to emptySet(),
            CLOUD_GCP to setOf(DOMAIN),
            BOT to setOf(DOMAIN, PERSISTENCE, CLOUD_GCP),
            INGEST to setOf(DOMAIN, PERSISTENCE, CLOUD_GCP),
            DASHBOARD to setOf(DOMAIN, PERSISTENCE, CLOUD_GCP),
            GOLDEN_RUNNER to setOf(DOMAIN),
            // 学習 Job（D-257）。bot とは独立した実行単位。学習ライブラリ（Hipparchus）はここだけ（:domain に入れない）
            ML_TRAIN to setOf(DOMAIN, PERSISTENCE, CLOUD_GCP),
        )

    /** `:domain` に入れてはいけない外部ライブラリ（group の前方一致）。 */
    val domainForbiddenGroups: List<String> =
        listOf(
            "org.springframework",
            "org.seasar.doma",
            "com.mysql",
            "mysql",
            "org.mariadb",
            "org.flywaydb",
            "com.zaxxer", // HikariCP
            "com.google.cloud",
            "com.google.auth",
            "com.google.api",
            "com.google.apis",
            "io.grpc",
            "software.amazon", // AWS SDK
            "com.azure",
            "com.fasterxml.jackson", // DTO・JSON はアプリ側（ARCHITECTURE §2.1）
            "io.ktor", // HTTP クライアント
            "com.squareup.okhttp3",
            "org.apache.httpcomponents",
            "org.apache.hc",
            "org.hibernate", // JPA / Entity
            "jakarta.persistence",
            "javax.persistence",
        )

    /**
     * Flyway は `:db-migration` だけが持つ（CONTRACTS §4.1: アプリの起動時にマイグレーションを実行しない。アプリのイメージに Flyway を入れない）。
     * Spring Boot の Flyway 自動構成（`spring-boot-flyway` / `spring-boot-starter-flyway`）もアーティファクト名で禁止する。
     */
    fun isFlyway(
        group: String?,
        name: String,
    ): Boolean = group?.startsWith("org.flywaydb") == true || name.contains("flyway", ignoreCase = true)

    /** `:db-migration` は他の実行単位と独立し、Spring に依存しない。 */
    val dbMigrationForbiddenGroups: List<String> = listOf("org.springframework", "org.seasar.doma")

    /** `:domain` から見て JDBC は標準ライブラリ（java.sql）なので、ソース側は別途ソース走査で検査する。 */
    val domainForbiddenImports: List<String> =
        listOf(
            "org.springframework.",
            "org.seasar.doma.",
            "java.sql.",
            "javax.sql.",
            "com.mysql.",
            "org.flywaydb.",
            "com.google.cloud.",
            "com.google.auth.",
            "com.google.api.",
            "io.grpc.",
            "software.amazon.",
            "com.azure.",
            "java.net.http.", // HTTP クライアント（JDK 標準）
            "java.net.HttpURLConnection",
            "okhttp3.",
            "org.apache.http.",
            "org.apache.hc.",
            "io.ktor.",
            "com.fasterxml.jackson.",
            "jakarta.persistence.",
            "javax.persistence.",
            "org.hibernate.",
        )

    /** バージョン管理だけを行う BOM（クラスを持ち込まない）は検査から除く。 */
    fun isBom(
        group: String?,
        name: String,
    ): Boolean = group == "org.springframework.boot" && name == "spring-boot-dependencies"

    /** プロジェクト間依存の違反を返す。入力は `プロジェクト -> 依存先プロジェクト集合`。 */
    fun checkProjectDependencies(graph: Map<String, Set<String>>): List<String> {
        val violations = mutableListOf<String>()
        for ((module, deps) in graph.toSortedMap()) {
            val allowed = allowedProjectDependencies[module]
            if (allowed == null) {
                violations += "$module: 未知のモジュール（jrabot の規則表に追加してください）"
                continue
            }
            for (dep in deps.toSortedSet()) {
                if (dep !in allowed) violations += "$module → $dep は禁止（許可: ${allowed.sorted().ifEmpty { listOf("なし") }}）"
            }
        }
        return violations
    }

    /** `:domain` の外部依存（group 名）の違反。 */
    fun checkDomainExternalGroups(groups: Collection<String>): List<String> =
        groups
            .sorted()
            .filter { g -> domainForbiddenGroups.any { g == it || g.startsWith("$it.") } }
            .map { ":domain → 外部ライブラリ $it は禁止" }

    /**
     * `:domain` のソースで、import を使わずに完全修飾名で直接参照している違反（例: `val c: java.sql.Connection`）。
     * 入力は `ファイル -> (行番号, 行)` の一覧（import 行・コメント行は呼び出し側で除く必要はない）。
     */
    fun checkDomainQualifiedReferences(codeLinesByFile: Map<String, List<Pair<Int, String>>>): List<String> =
        codeLinesByFile.toSortedMap().flatMap { (file, lines) ->
            lines
                .filter { (_, line) ->
                    val t = line.trim()
                    !t.startsWith("import ") && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
                }.flatMap { (no, line) ->
                    domainForbiddenImports.filter { line.contains(it) }.map { "$file:$no: $it を完全修飾名で参照しているのは :domain では禁止" }
                }
        }

    /** `:domain` のソース内の import の違反。 */
    fun checkDomainImports(importsByFile: Map<String, List<String>>): List<String> =
        importsByFile.toSortedMap().flatMap { (file, imports) ->
            imports
                .filter { imp -> domainForbiddenImports.any { imp.startsWith(it) } }
                .map { "$file: import $it は :domain では禁止" }
        }

    /** 入力は `モジュール -> 宣言された外部依存("group:name")`。Flyway が `:db-migration` 以外にあれば違反。 */
    fun checkFlywayOnlyInDbMigration(externalByModule: Map<String, Set<String>>): List<String> =
        externalByModule
            .toSortedMap()
            .filterKeys { it != DB_MIGRATION }
            .flatMap { (module, deps) ->
                deps
                    .sorted()
                    .filter { isFlyway(it.substringBefore(':'), it.substringAfter(':')) }
                    .map { dep -> "$module → $dep は禁止（Flyway は :db-migration だけ）" }
            }

    /** `:db-migration` の外部依存の違反（Spring・Doma に依存しない）。 */
    fun checkDbMigrationExternal(deps: Collection<String>): List<String> =
        deps
            .sorted()
            .filter { dep -> dbMigrationForbiddenGroups.any { dep.substringBefore(':') == it || dep.substringBefore(':').startsWith("$it.") } }
            .map { ":db-migration → 外部ライブラリ $it は禁止" }
}
