package jrabot.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider

/** ルートに適用。ktlint CLI（Maven Central の all jar）で全 .kt を検査する（`ktlintCheck`）。`-Pformat` で自動整形。 */
class KtlintPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "jrabot.ktlint はルートプロジェクトにだけ適用します" }
        target.pluginManager.apply("base")
        val libs = target.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val version = libs.findVersion("ktlint").get().requiredVersion
        val cfg = target.configurations.create("ktlint")
        target.dependencies.add("ktlint", "com.pinterest.ktlint:ktlint-cli:$version:all")

        val globs = listOf("**/*.kt", "**/*.kts", "!**/build/**", "!**/.gradle/**", "!**/.kotlin/**")
        val check: TaskProvider<JavaExec> =
            target.tasks.register("ktlintCheck", JavaExec::class.java) {
                it.group = "verification"
                it.description = "ktlint で Kotlin のコードスタイルを検査する"
                it.classpath = cfg
                it.mainClass.set("com.pinterest.ktlint.Main")
                it.args(globs)
                it.workingDir = target.projectDir
            }
        target.tasks.register("ktlintFormat", JavaExec::class.java) {
            it.group = "formatting"
            it.description = "ktlint で Kotlin のコードを自動整形する"
            it.classpath = cfg
            it.mainClass.set("com.pinterest.ktlint.Main")
            it.args(listOf("-F") + globs)
            it.workingDir = target.projectDir
        }
        target.tasks.named("check") { it.dependsOn(check) }
    }
}
