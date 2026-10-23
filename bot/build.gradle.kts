plugins {
    id("jrabot.spring-boot-app")
}

// このアプリが必要とする最小のスキーマ版（CONTRACTS §4.3。/internal/ready と Job の開始時に flyway_schema_history と比較する）。
// V1/V2 の表を使うコードなので 2。使う表が増える V3 以降のコードを入れるときに上げる。
extra["requiredSchemaVersion"] = "2"

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":cloud-gcp"))
    implementation(libs.spring.boot.starter)

    // テスト専用: 偽の JWKS・MySQL コンテナの支援。HikariCP の型は DataSource の状態確認（接続していないこと）に使う
    testImplementation(testFixtures(project(":persistence")))
    testImplementation(testFixtures(project(":cloud-gcp")))
    testImplementation(libs.spring.boot.starter.jdbc)
}
