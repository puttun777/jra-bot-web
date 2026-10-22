package jrabot.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/** bot / ingest / dashboard 用: Kotlin 共通設定 + Spring Boot（実行可能 jar、kotlin-spring）。 */
class SpringBootAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply(KotlinModulePlugin::class.java)
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
        target.pluginManager.apply("org.springframework.boot")
        val libs = target.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        target.dependencies.add("testImplementation", libs.findLibrary("spring-boot-starter-test").get())
    }
}
