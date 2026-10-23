plugins {
    id("jrabot.kotlin-module")
    application
}

// ゴールデンテストの Kotlin 側ランナー（T3.1）。`tools/golden` のフィクスチャを読み、:domain の計算を実行して結果 JSON を出力する。
// JSON の読み書きはここだけが持つ（:domain は Jackson に依存しない。checkModuleDependencies が強制）。
// 検証専用のツールで、アプリのイメージ（bot / ingest / dashboard / db-migration）には含めない。
dependencies {
    implementation(project(":domain"))
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("jp.jrabot.golden.GoldenRunnerKt")
}
