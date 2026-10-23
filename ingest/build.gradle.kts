plugins {
    id("jrabot.spring-boot-app")
}

// このアプリが必要とする最小のスキーマ版（CONTRACTS §4.3。/internal/ready と Job の開始時に flyway_schema_history と比較する）。
// V3（ingest_fetch・ingest_backfill・agent の署名鍵列）を使うので 3。
extra["requiredSchemaVersion"] = "3"

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":cloud-gcp"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.databind) // エージェント API の JSON（アプリ側。ARCHITECTURE §2.1）
    implementation(libs.spring.boot.starter.jdbc) // DB 失敗の例外型（spring-tx / spring-jdbc）で 503 と 500 を分ける

    // テスト専用: 偽の JWKS・MySQL コンテナの支援。HikariCP の型は DataSource の状態確認（接続していないこと）に使う
    testImplementation(testFixtures(project(":persistence")))
    testImplementation(testFixtures(project(":cloud-gcp")))
    testImplementation(libs.spring.boot.starter.jdbc)
}
