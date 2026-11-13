plugins {
    id("jrabot.spring-boot-app")
}

// このアプリが必要とする最小のスキーマ版（CONTRACTS §4.3。/internal/ready と Job の開始時に flyway_schema_history と比較する）。
// 3 アプリで同じ値にする（release-manifest の検証規則: tools/src/release/manifest.ts）。V3・V4 の表は dashboard 自身はまだ使わない（app の SELECT 権限だけ付与済み）。
extra["requiredSchemaVersion"] = "5"

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":platform"))
    implementation(project(":cloud-gcp"))
    implementation(project(":cloud-aws"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc) // TransactionOperations（管理操作と監査記録を同じトランザクションにする）

    // テスト専用: 偽の JWKS・MySQL コンテナの支援。HikariCP の型は DataSource の状態確認（接続していないこと）に使う
    testImplementation(testFixtures(project(":persistence")))
    testImplementation(testFixtures(project(":cloud-gcp")))
    testImplementation(libs.spring.boot.starter.jdbc)
}

// ---------------------------------------------------------------- 画面（TypeScript。D-263）
// dashboard/src/main/ts を tsc（ルートの package-lock.json で固定した typescript）で ES モジュールへ変換し、jar の static/js/ に入れる。
// 生成物は build/ の下だけ（Git に入れない）。Node と `npm ci --ignore-scripts` が先に要る（無ければ理由を示して失敗する。黙って画面の無い jar を作らない）。
// Docker のビルドは Node のステージで同じ tsc を実行して結果をここへ置き、-PfrontendPrebuilt=true でこのタスクを省く（Dockerfile）。
val frontendOut = layout.buildDirectory.dir("generated/frontend")
val frontendPrebuilt = providers.gradleProperty("frontendPrebuilt").map { it == "true" }.orElse(false)
val rootDirFile = rootProject.layout.projectDirectory.asFile
val tscFile =
    rootProject.layout.projectDirectory
        .file("node_modules/typescript/bin/tsc")
        .asFile
val frontendTsconfig = layout.projectDirectory.file("tsconfig.json").asFile

val compileFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "画面の TypeScript（src/main/ts）を static/js へ変換する"
    inputs.dir("src/main/ts")
    inputs.file(frontendTsconfig)
    inputs.file(rootProject.layout.projectDirectory.file("package-lock.json"))
    // 入っている typescript の版（lockfile が同じでも node_modules が古い場合に up-to-date にしない。無ければ空の入力で、doFirst が理由を示して失敗する）
    inputs.files(rootProject.layout.projectDirectory.file("node_modules/typescript/package.json"))
    outputs.dir(frontendOut)
    onlyIf("-PfrontendPrebuilt=true のときは Docker の Node ステージの結果を使う") { !frontendPrebuilt.get() }
    workingDir(rootDirFile)
    commandLine("node", tscFile.path, "-p", frontendTsconfig.path)
    doFirst {
        if (!tscFile.isFile) {
            throw GradleException("TypeScript が無い: リポジトリの最上位で `npm ci --ignore-scripts` を実行する（Node 24。VERSIONS §16）")
        }
        frontendOut.get().asFile.deleteRecursively()
    }
}

sourceSets.main {
    resources.srcDir(files(frontendOut).builtBy(compileFrontend))
}
