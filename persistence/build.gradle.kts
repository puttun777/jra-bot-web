plugins {
    id("jrabot.kotlin-module")
    id("org.jetbrains.kotlin.kapt")
    `java-test-fixtures`
}

// Doma・Spring JDBC/TX・HikariCP・Connector/J は :persistence に隔離する（:domain には入れない。checkModuleDependencies が強制）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.doma.core)
    implementation(libs.doma.kotlin)
    kapt(libs.doma.processor)
    implementation(libs.doma.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc) // HikariCP + spring-jdbc + spring-tx
    runtimeOnly(libs.mysql.connector.j)

    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.mysql.connector.j)

    // MySQL コンテナの支援（MySqlSupport・TestDb・TcpProxy）は testFixtures に置き、:persistence と 3 アプリの統合テストが共用する。
    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(libs.testcontainers.mysql)
    testFixturesImplementation(libs.mysql.connector.j)
    testFixturesImplementation(libs.kotlin.test.junit5)
}

// G-DB-0b: Connector/J 26.x の互換性確認用。`-PconnectorJVersion=26.10.0` で版だけを差し替える（既定は Boot の BOM の 9.7.0）。
// 採用はしない（成功しても D-070 の記録後に判断する）。
val connectorJOverride: String? = providers.gradleProperty("connectorJVersion").orNull
if (connectorJOverride != null) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.mysql" && requested.name == "mysql-connector-j") useVersion(connectorJOverride)
        }
    }
}

kapt {
    arguments {
        // SQL テンプレート（META-INF/<パッケージ>/<Dao>/<メソッド>.sql）の存在をコンパイル時に検査させる
        arg("doma.resources.dir", file("src/main/resources").absolutePath)
    }
}

// 通常の test は Docker 不要の単体テストだけ。Docker（Testcontainers）を使う統合テストは integrationTest で実行する（:db-migration と同じ方式）。
// MySQL の版は環境変数 MYSQL_IMAGE（既定 mysql:8.4）。Docker が無ければ**失敗する**（スキップしない）。
// スキーマは本物の db-migration CLI（installDist）を子プロセスで実行して作る（Flyway を :persistence に入れない）。
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Docker 上の MySQL（MYSQL_IMAGE）に対する Repository の統合テスト。Docker が無ければ失敗する"
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
    systemProperty("expected.connectorj.version", connectorJOverride ?: "")
    // テストの再利用（キャッシュ）で DB 検証を飛ばさない
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = false
}
