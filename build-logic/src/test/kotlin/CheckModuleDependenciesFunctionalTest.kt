package jrabot.build

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** 実際の Gradle ビルドで `checkModuleDependencies` が違反を検知して失敗することを確認する（TestKit、外部ライブラリ不要）。 */
class CheckModuleDependenciesFunctionalTest {
    private fun fixture(extra: Map<String, String> = emptyMap()): File {
        val dir = createTempDirectory("jrabot-fixture").toFile()
        val modules = DependencyRules.modules.map { it.removePrefix(":") }
        File(dir, "settings.gradle.kts").writeText(
            "rootProject.name = \"fixture\"\ninclude(${modules.joinToString { "\":$it\"" }})\n",
        )
        File(dir, "build.gradle.kts").writeText("plugins { id(\"jrabot.module-dependency-rules\") }\n")
        for (m in modules) {
            File(dir, "$m/src/main/kotlin").mkdirs()
            val deps =
                extra[m] ?: DependencyRules.allowedProjectDependencies
                    .getValue(":$m")
                    .joinToString("\n") { "    implementation(project(\"$it\"))" }
            File(dir, "$m/build.gradle.kts").writeText("plugins { `java-library` }\ndependencies {\n$deps\n}\n")
        }
        return dir
    }

    private fun runner(dir: File) =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments("checkModuleDependencies", "--stacktrace")

    @Test
    fun `設計どおりなら成功する`() {
        val result = runner(fixture()).build()
        assertContains(result.output, "checkModuleDependencies: OK")
    }

    @Test
    fun `bot から dashboard への依存を検知して失敗する`() {
        val dir = fixture(mapOf("bot" to "    implementation(project(\":domain\"))\n    implementation(project(\":dashboard\"))"))
        val result = runner(dir).buildAndFail()
        assertContains(result.output, ":bot → :dashboard は禁止")
    }

    @Test
    fun `domain が Spring に依存すると失敗する`() {
        val dir = fixture(mapOf("domain" to "    implementation(\"org.springframework:spring-core:7.0.0\")"))
        // 依存の宣言だけを検査するので解決（ネットワーク）は発生しない
        val result = runner(dir).buildAndFail()
        assertContains(result.output, ":domain → 外部ライブラリ org.springframework は禁止")
    }

    @Test
    fun `domain のソースが java_sql を import すると失敗する`() {
        val dir = fixture()
        File(dir, "domain/src/main/kotlin/Bad.kt").writeText("import java.sql.Connection\n\nclass Bad\n")
        val result = runner(dir).buildAndFail()
        assertTrue("java.sql." in result.output, result.output)
    }
}
