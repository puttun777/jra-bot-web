package jrabot.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** 全モジュール共通の Kotlin/JVM 設定。JDK は gradle.properties の jdkVersion（既定 25）のツールチェーン。 */
class KotlinModulePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlin.jvm")
        val libs = target.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val jdk = (target.findProperty("jdkVersion") as String?)?.toInt() ?: 25

        target.group = "jp.jrabot"
        target.version = "0.1.0-SNAPSHOT"

        target.extensions.configure(KotlinJvmProjectExtension::class.java) {
            it.jvmToolchain(jdk)
            it.compilerOptions.allWarningsAsErrors.set(true) // 静的解析の第一段（警告をエラーにする）
        }

        target.dependencies.add("implementation", target.dependencies.platform(libs.findLibrary("spring-boot-dependencies").get()))
        target.dependencies.add("testImplementation", libs.findLibrary("junit-jupiter").get())
        target.dependencies.add("testImplementation", libs.findLibrary("kotlin-test-junit5").get())
        target.dependencies.add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())

        // 再現可能なビルド（T2.5 後半）: アーカイブのタイムスタンプとファイル順を固定し、同じソースから同じ jar になるようにする
        target.tasks.withType(AbstractArchiveTask::class.java).configureEach {
            it.isPreserveFileTimestamps = false
            it.isReproducibleFileOrder = true
        }

        target.tasks.withType(Test::class.java).configureEach {
            it.useJUnitPlatform()
            it.testLogging { l ->
                l.events("passed", "skipped", "failed")
                l.exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}
