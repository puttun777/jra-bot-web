plugins {
    id("jrabot.kotlin-module")
    application
}

// 旧 SQLite（参照元 jra-bot の data/jraev.sqlite のコピー）→ MySQL の移行ツール（D-271。DATA_MIGRATION_DESIGN §5）。
// 利用者の PC で実行する CLI で、アプリのイメージには含めない。段階: preflight・extract・transform・load（D-271〜D-275）。
// 実データは Git に入れない。試験は合成の SQLite だけ（COMP-4: 実データのクラウド投入は回答待ち）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.mysql.connector.j) // load（P3）の接続先。版は Boot の BOM（jrabot.kotlin-module）

    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.mysql.connector.j)
}

application {
    mainClass.set("jp.jrabot.legacyimport.LegacyImportKt")
}

// 通常の test は Docker 不要の単体テストだけ。load の統合テスト（Testcontainers の MySQL に本物の db-migration CLI で V1〜V5 を適用）は integrationTest。
// MySQL の版は環境変数 MYSQL_IMAGE（既定 mysql:8.4）。Docker が無ければ失敗する（スキップしない）。
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Docker 上の MySQL（MYSQL_IMAGE）に対する load の統合テスト。Docker が無ければ失敗する"
    dependsOn(":db-migration:installDist")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    environment("MYSQL_IMAGE", System.getenv("MYSQL_IMAGE") ?: "mysql:8.4")
    systemProperty(
        "dbmigration.install.dir",
        project(":db-migration")
            .layout.buildDirectory
            .dir("install/db-migration")
            .get()
            .asFile.path,
    )
    outputs.upToDateWhen { false }
}
