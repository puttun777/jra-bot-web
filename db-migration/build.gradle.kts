plugins {
    id("jrabot.kotlin-module")
    application
}

// 他のモジュールに依存しない独立した実行単位（Flyway はこのモジュールだけが持つ: CONTRACTS §4.1）。
dependencies {
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.mysql.connector.j)
}

application {
    mainClass.set("jp.jrabot.dbmigration.DbMigrationMainKt")
}

// 通常の test は Docker 不要の単体テストだけ。Docker（Testcontainers）を使う統合テストは integrationTest で実行する。
// MySQL の版は環境変数 MYSQL_IMAGE（既定 mysql:8.4）。Docker が無ければ**失敗する**（スキップしない）。
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Docker 上の MySQL（MYSQL_IMAGE）に対する統合テスト。Docker が無ければ失敗する"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    environment("MYSQL_IMAGE", System.getenv("MYSQL_IMAGE") ?: "mysql:8.4")
    // テストの再利用（キャッシュ）で DB 検証を飛ばさない
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = false
}
