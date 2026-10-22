plugins {
    id("jrabot.kotlin-module")
}

dependencies {
    implementation(project(":domain"))
    // Doma・Spring JDBC は T2.4 で導入する。
}
