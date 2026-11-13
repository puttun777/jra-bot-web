plugins {
    id("jrabot.kotlin-module")
    `java-test-fixtures`
}

// provider 中立の実行基盤の部品（D-280）: 運用モードの読取り（キャッシュ・検証・seq の検査）、内部 API と人の認証の型、
// オブジェクトストレージ上の ML 成果物の保管。クラウドの SDK には依存しない（GCS は :cloud-gcp、S3 は :cloud-aws が実装する）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}

// 運用モードの試験の補助（正本の JSON の生成・時計・ログの記録）。:cloud-gcp・:cloud-aws の試験も使う
dependencies {
    testFixturesApi(project(":domain"))
    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(libs.slf4j.api)
}
