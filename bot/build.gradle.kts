plugins {
    id("jrabot.spring-boot-app")
}

// このアプリが必要とする最小のスキーマ版（CONTRACTS §4.3。/internal/ready と Job の開始時に flyway_schema_history と比較する）。
// V3 の ingest_fetch（取得の事実。ScheduleFetchFactReader）と V4 の horse_master_history（export-dataset が時点ごとの馬マスタを読む）を使うので 4。
extra["requiredSchemaVersion"] = "5"

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

// export-dataset → ml-train → 推論側の読込みの結合試験（MlTrainInteropIntegrationTest）は ml-train の実行 jar を**別プロセス**で起動する。
// ml-train への依存はタスクの順序だけ（クラスパスには入れない: bot と ml-train は相互に依存しない。DependencyRules）
tasks.named<Test>("integrationTest") {
    dependsOn(":ml-train:bootJar")
    systemProperty(
        "mltrain.jar",
        project(":ml-train")
            .layout.buildDirectory
            .file("libs/ml-train.jar")
            .get()
            .asFile.path,
    )
}
