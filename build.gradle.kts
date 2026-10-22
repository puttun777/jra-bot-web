plugins {
    id("jrabot.module-dependency-rules")
    id("jrabot.ktlint")
}

// build-logic（規約プラグイン）のテスト（依存規則そのものの検証）も root の check に含める
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
