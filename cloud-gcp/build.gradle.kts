plugins {
    id("jrabot.kotlin-module")
    `java-test-fixtures`
}

// Google Cloud のクライアント・JSON・ログは :cloud-gcp に隔離する（:domain には入れない。checkModuleDependencies が強制）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.google.cloud.storage)
    implementation(libs.google.auth.oauth2.http)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}

// 内部 API 認証のテストで、:cloud-gcp 自身とアプリが共用する（偽の JWKS サーバと署名済みトークンの生成。実 Google には接続しない）。
dependencies {
    testFixturesApi(project(":domain"))
}
