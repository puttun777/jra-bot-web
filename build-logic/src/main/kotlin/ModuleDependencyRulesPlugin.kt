package jrabot.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskAction

/** ルートプロジェクトに適用し、`checkModuleDependencies` を `check` に組み込む。 */
class ModuleDependencyRulesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "jrabot.module-dependency-rules はルートプロジェクトにだけ適用します" }
        target.pluginManager.apply("base")
        val task =
            target.tasks.register("checkModuleDependencies", CheckModuleDependenciesTask::class.java) {
                it.group = "verification"
                it.description = "モジュール間・外部ライブラリの依存方向を検査する（違反があれば失敗）"
            }
        target.tasks.named("check") { it.dependsOn(task) }
    }
}

abstract class CheckModuleDependenciesTask : DefaultTask() {
    @TaskAction
    fun check() {
        val moduleNames = project.subprojects.map { it.path }.toSet()
        val graph = mutableMapOf<String, Set<String>>()
        val domainGroups = mutableSetOf<String>()
        val importsByFile = mutableMapOf<String, List<String>>()
        val codeLinesByFile = mutableMapOf<String, List<Pair<Int, String>>>()

        for (sub in project.subprojects) {
            val deps = mutableSetOf<String>()
            // すべての構成（テスト用を含む）の宣言済み依存を見る。解決はしない（ネットワーク不要）。
            for (conf in sub.configurations) {
                for (d in conf.dependencies) {
                    // java-test-fixtures などが付ける自分自身への依存は除く
                    if (d is ProjectDependency && d.path != sub.path) deps += d.path
                    if (sub.path == DependencyRules.DOMAIN && d is ExternalModuleDependency && !DependencyRules.isBom(d.group, d.name)) {
                        domainGroups += d.group
                    }
                }
            }
            graph[sub.path] = deps
            if (sub.path == DependencyRules.DOMAIN) {
                sub.projectDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }
                    .forEach { f ->
                        val key = f.relativeTo(project.projectDir).path
                        val lines = f.readLines()
                        importsByFile[key] =
                            lines
                                .map { it.trim() }
                                .filter { it.startsWith("import ") }
                                .map { it.removePrefix("import ") }
                        codeLinesByFile[key] = lines.mapIndexed { i, l -> (i + 1) to l }
                    }
            }
        }

        val violations =
            DependencyRules.checkProjectDependencies(graph) +
                DependencyRules.checkDomainExternalGroups(domainGroups) +
                DependencyRules.checkDomainImports(importsByFile) +
                DependencyRules.checkDomainQualifiedReferences(codeLinesByFile) +
                (DependencyRules.modules.filter { it !in moduleNames }.map { "$it が settings.gradle.kts に無い" })
        if (violations.isNotEmpty()) {
            throw GradleException("モジュール依存の違反 ${violations.size} 件:\n" + violations.joinToString("\n") { " - $it" })
        }
        logger.lifecycle("checkModuleDependencies: OK（${graph.size} モジュール）")
    }
}
