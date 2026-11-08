plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.github.triplet.play") version "4.0.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    alias(libs.plugins.spotless) apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    // On the root classpath, as the Flutter app template's settings plugins
    // block has it: the Flutter plugin projects' build scripts then see the
    // FlutterExtension type their `flutter.compileSdkVersion` accessor needs,
    // and its kotlin-gradle-plugin 2.0.0 dependency resolves to the 2.4.10
    // above instead of loading a second compiler on :app's classpath.
    id("dev.flutter.flutter-gradle-plugin") apply false
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("${projectDir}/config/detekt/detekt.yml")
    baseline = file("${projectDir}/config/detekt/baseline.xml")
    source.setFrom("app/src/main/java", "app/src/main/kotlin")
}

dependencies {
    "detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

// Flutter plugin projects (settings.gradle.kts) are configured after :app, as in
// the Flutter app template.
subprojects {
    if (path != ":app" && path != ":baselineprofile") {
        evaluationDependsOn(":app")
    }
}
