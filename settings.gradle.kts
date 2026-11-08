import groovy.json.JsonSlurper

// The UI is FlClash's Flutter app in flutter/ (see flclash.md). Gradle needs the
// Flutter SDK for its Gradle plugin: flutter.sdk in local.properties, or the
// FLUTTER_ROOT that subosito/flutter-action exports in CI.
val flutterSdkPath: String = run {
    val properties = java.util.Properties()
    val localProperties = file("local.properties")
    if (localProperties.exists()) localProperties.inputStream().use { properties.load(it) }
    properties.getProperty("flutter.sdk") ?: System.getenv("FLUTTER_ROOT")
        ?: error("flutter.sdk not set in local.properties and FLUTTER_ROOT is unset")
}

pluginManagement {
    val flutterSdk: String = run {
        val properties = java.util.Properties()
        val localProperties = file("local.properties")
        if (localProperties.exists()) localProperties.inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk") ?: System.getenv("FLUTTER_ROOT")
            ?: error("flutter.sdk not set in local.properties and FLUTTER_ROOT is unset")
    }
    includeBuild("$flutterSdk/packages/flutter_tools/gradle")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // The Flutter Gradle plugin adds the engine repository to each project.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroup("io.github.sagernet") }
        }
    }
}
rootProject.name = "FK-box"
include(":app")
// Baseline Profile generator, only with -PbaselineProfile=true (see app.md).
if (providers.gradleProperty("baselineProfile").orNull.toBoolean()) {
    include(":baselineprofile")
}

// Flutter plugins with Android code, as FlutterAppPluginLoaderPlugin includes
// them for a Flutter app whose android/ sits inside the Flutter project. Here the
// Flutter project is flutter/ beside app/, so the same list is read from its
// .flutter-plugins-dependencies (written by `flutter pub get`).
val flutterPlugins = file("flutter/.flutter-plugins-dependencies")
check(flutterPlugins.exists()) {
    "Run `flutter pub get` in flutter/ before Gradle (flutter/.flutter-plugins-dependencies is missing)"
}
@Suppress("UNCHECKED_CAST")
val androidPlugins = ((JsonSlurper().parseText(flutterPlugins.readText()) as Map<String, Any?>)["plugins"]
    as Map<String, Any?>)["android"] as List<Map<String, Any?>>? ?: emptyList()
for (plugin in androidPlugins) {
    if (plugin["native_build"] as? Boolean == false) continue
    val name = plugin["name"] as String
    val directory = File(plugin["path"] as String, "android")
    check(directory.exists()) { "Plugin directory does not exist: ${directory.absolutePath}" }
    include(":$name")
    project(":$name").projectDir = directory
}
