plugins {
    `java-gradle-plugin`
    // build-logic 自体も Kotlin 2.4.21 でコンパイルする（Gradle 同梱の Kotlin より新しい KGP を依存に取るため）
    kotlin("jvm") version libs.versions.kotlin.get()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        register("moduleDependencyRules") {
            id = "jrabot.module-dependency-rules"
            implementationClass = "jrabot.build.ModuleDependencyRulesPlugin"
        }
        register("ktlint") {
            id = "jrabot.ktlint"
            implementationClass = "jrabot.build.KtlintPlugin"
        }
        register("kotlinModule") {
            id = "jrabot.kotlin-module"
            implementationClass = "jrabot.build.KotlinModulePlugin"
        }
        register("springBootApp") {
            id = "jrabot.spring-boot-app"
            implementationClass = "jrabot.build.SpringBootAppPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
