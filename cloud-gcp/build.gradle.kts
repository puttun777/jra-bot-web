plugins {
    id("jrabot.kotlin-module")
}

dependencies {
    implementation(project(":domain"))
    // GCS / Cloud Run Jobs クライアントは T2.7 以降で導入する。
}
