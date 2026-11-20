// P0 feasibility probe (docs/kernel-isolation.md); built only by
// .github/workflows/kernel-feasibility.yml with clients/android's Gradle wrapper.
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
    }
}
rootProject.name = "kernel-probe"
include(":app")
