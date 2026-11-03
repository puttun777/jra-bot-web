rootProject.name = "jra-bot-web"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":domain",
    ":persistence",
    ":db-migration",
    ":cloud-gcp",
    ":bot",
    ":ingest",
    ":dashboard",
    ":golden-runner",
    ":ml-train",
)
