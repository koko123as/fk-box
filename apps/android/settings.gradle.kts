pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroup("io.github.sagernet") }
        }
    }
}
rootProject.name = "fk-box"
// The Gradle root is apps/android/ (run ./gradlew from here); the repository
// root above it holds the Cargo workspace, version.properties and resources/
// (see AGENTS.md, "目录结构").
include(":app")
// Baseline Profile generator, only with -PbaselineProfile=true (see docs/app.md).
if (providers.gradleProperty("baselineProfile").orNull.toBoolean()) {
    include(":baselineprofile")
}
