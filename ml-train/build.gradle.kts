plugins {
    id("jrabot.spring-boot-app")
}

// ML 学習 Job（D-257。旧 Python の ml/ を Kotlin へ移植）。bot とは独立した実行単位・イメージ。登録は CANDIDATE / REJECTED だけ（ACTIVE にしない）。
// ml_model（V2）と ml_dataset 系の表を使う。他アプリと同じスキーマ版を要求する（release-manifest の検証規則: 全アプリで同一）
extra["requiredSchemaVersion"] = "5"

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":cloud-gcp"))
    implementation(libs.spring.boot.starter)
    implementation(libs.hipparchus.core)
    implementation(libs.hipparchus.stat)

    testImplementation(testFixtures(project(":persistence")))
    testImplementation(testFixtures(project(":cloud-gcp")))
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.jackson.databind)
}
