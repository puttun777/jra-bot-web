plugins {
    id("jrabot.spring-boot-app")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":cloud-gcp"))
    implementation(libs.spring.boot.starter.webmvc)
}
