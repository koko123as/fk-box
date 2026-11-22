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
// The Gradle root stays at the repository root (CI runs ./gradlew from here);
// the modules live under apps/android/ (see AGENTS.md, "目录结构").
include(":app")
project(":app").projectDir = file("apps/android/app")
// Baseline Profile generator, only with -PbaselineProfile=true (see app.md).
if (providers.gradleProperty("baselineProfile").orNull.toBoolean()) {
    include(":baselineprofile")
    project(":baselineprofile").projectDir = file("apps/android/baselineprofile")
}
