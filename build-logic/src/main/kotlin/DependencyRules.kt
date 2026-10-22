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

    val modules = listOf(DOMAIN, PERSISTENCE, DB_MIGRATION, CLOUD_GCP, BOT, INGEST, DASHBOARD)
    val apps = setOf(BOT, INGEST, DASHBOARD)

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
        )

    /** `:domain` から見て JDBC は標準ライブラリ（java.sql）なので、ソース側は別途ソース走査で検査する。 */
    val domainForbiddenImports: List<String> =
        listOf("org.springframework.", "org.seasar.doma.", "java.sql.", "javax.sql.", "com.google.cloud.", "com.mysql.")

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

    /** `:domain` のソース内の import の違反。 */
    fun checkDomainImports(importsByFile: Map<String, List<String>>): List<String> =
        importsByFile.toSortedMap().flatMap { (file, imports) ->
            imports
                .filter { imp -> domainForbiddenImports.any { imp.startsWith(it) } }
                .map { "$file: import $it は :domain では禁止" }
        }
}
