plugins {
    id("jrabot.spring-boot-app")
}

// ML 学習 Job（D-257。旧 Python の ml/ を Kotlin へ移植）。bot とは独立した実行単位・イメージ。登録は CANDIDATE / REJECTED だけ（ACTIVE にしない）。
// ml_model（V2）を使う。requiredSchemaVersion は release-manifest に宣言するだけで、実行時には確認しない（mltrain は flyway_schema_history を読めない。
// deploy が migrate を先に行う）。値は他アプリと同じにする（release-manifest の検証規則: 全アプリで同一）
extra["requiredSchemaVersion"] = "5"

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":platform"))
    implementation(project(":cloud-gcp"))
    implementation(project(":cloud-aws"))
    implementation(libs.spring.boot.starter)
    implementation(libs.hipparchus.core)
    implementation(libs.hipparchus.stat)

    testImplementation(testFixtures(project(":persistence")))
    testImplementation(testFixtures(project(":cloud-gcp")))
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.jackson.databind)
}
