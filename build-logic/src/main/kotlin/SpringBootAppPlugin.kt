package jrabot.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.testing.Test

/**
 * bot / ingest / dashboard 用: Kotlin 共通設定 + Spring Boot（実行可能 jar、kotlin-spring）。
 *
 * ビルド時の焼き込み（CONTRACTS §2・§4.3）: `jrabot-app.properties` を生成して実行 jar に入れる。
 * - `requiredSchemaVersion`: そのアプリが必要とする最小のスキーマ版。各アプリの `build.gradle.kts` で `extra["requiredSchemaVersion"]` に設定する（必須。無ければビルドが失敗する）。
 * - `releaseId`: `-PreleaseId=<git sha>` で渡す（既定 `dev`）。
 */
class SpringBootAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply(KotlinModulePlugin::class.java)
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
        target.pluginManager.apply("org.springframework.boot")
        val libs = target.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        target.dependencies.add("testImplementation", libs.findLibrary("spring-boot-starter-test").get())

        val appInfo =
            target.tasks.register("generateAppInfo", WriteProperties::class.java) { t ->
                t.description = "ビルド時に焼き込む jrabot-app.properties（必要なスキーマ版・リリース ID）を生成する"
                t.destinationFile.set(target.layout.buildDirectory.file("generated/appinfo/jrabot-app.properties"))
                t.property(
                    "requiredSchemaVersion",
                    target.provider {
                        requireNotNull(target.extensions.extraProperties.properties["requiredSchemaVersion"]) {
                            "${target.path}: extra[\"requiredSchemaVersion\"] を build.gradle.kts で設定してください（CONTRACTS §4.3）"
                        }
                    },
                )
                t.property("releaseId", target.providers.gradleProperty("releaseId").orElse("dev"))
            }
        target.tasks.named("processResources", Copy::class.java) { it.from(appInfo) }

        // 通常の test は Docker 不要の起動・単体テストだけ。MySQL（Testcontainers）を使う統合テストは integrationTest で実行する
        // （:persistence と同じ方式。MYSQL_IMAGE が MySQL の版。Docker が無ければ失敗し、スキップしない。スキーマは本物の db-migration CLI で適用する）。
        target.tasks.named("test", Test::class.java) { it.useJUnitPlatform { o -> o.excludeTags("integration") } }
        val sourceSets = target.extensions.getByType(SourceSetContainer::class.java)
        target.tasks.register("integrationTest", Test::class.java) { t ->
            t.group = "verification"
            t.description = "Docker 上の MySQL（MYSQL_IMAGE）を使う統合テスト。Docker が無ければ失敗する"
            t.dependsOn(":db-migration:installDist")
            t.testClassesDirs = sourceSets.getByName("test").output.classesDirs
            t.classpath = sourceSets.getByName("test").runtimeClasspath
            t.useJUnitPlatform { o -> o.includeTags("integration") }
            t.environment("MYSQL_IMAGE", System.getenv("MYSQL_IMAGE") ?: "mysql:8.4")
            t.systemProperty(
                "dbmigration.install.dir",
                target
                    .project(":db-migration")
                    .layout.buildDirectory
                    .dir("install/db-migration")
                    .get()
                    .asFile.path,
            )
            t.outputs.upToDateWhen { false } // テストの再利用（キャッシュ）で DB 検証を飛ばさない
        }
    }
}
