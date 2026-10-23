plugins {
    id("jrabot.kotlin-module")
}

// Google Cloud のクライアント・JSON・ログは :cloud-gcp に隔離する（:domain には入れない。checkModuleDependencies が強制）。
dependencies {
    implementation(project(":domain"))
    implementation(libs.google.cloud.storage)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}
