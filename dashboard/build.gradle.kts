plugins {
    id("jrabot.spring-boot-app")
}

// このアプリが必要とする最小のスキーマ版（CONTRACTS §4.3。/internal/ready と Job の開始時に flyway_schema_history と比較する）。
// 3 アプリで同じ値にする（release-manifest の検証規則: tools/src/release/manifest.ts）。V3・V4 の表は dashboard 自身はまだ使わない（app の SELECT 権限だけ付与済み）。
extra["requiredSchemaVersion"] = "5"

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":cloud-gcp"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc) // TransactionOperations（管理操作と監査記録を同じトランザクションにする）

    // テスト専用: 偽の JWKS・MySQL コンテナの支援。HikariCP の型は DataSource の状態確認（接続していないこと）に使う
    testImplementation(testFixtures(project(":persistence")))
    testImplementation(testFixtures(project(":cloud-gcp")))
    testImplementation(libs.spring.boot.starter.jdbc)
}
