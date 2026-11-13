plugins {
    id("jrabot.kotlin-module")
}

// AWS の実装（D-280）: S3 の運用モードの正本の読取り・ML 成果物の保管・エージェントの最終接続の写し、Cognito の ID トークンの検証。
// AWS SDK は S3 だけ（HTTP は JDK の URLConnection 版。Netty・Apache HttpClient を入れない）。Cognito の検証は JDK の暗号と HttpClient で行う。
// :cloud-gcp には依存しない（checkModuleDependencies が強制）。
dependencies {
    implementation(project(":domain"))
    api(project(":platform"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.url.connection.client)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
}

dependencies {
    testImplementation(testFixtures(project(":platform")))
}
