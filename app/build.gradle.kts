import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.github.triplet.play")
    alias(libs.plugins.spotless)
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

fun getProps(propName: String): String {
    System.getenv(propName)?.let { return it }
    val propsInEnv = System.getenv("LOCAL_PROPERTIES")
    if (propsInEnv != null) {
        val props = Properties()
        props.load(ByteArrayInputStream(Base64.getDecoder().decode(propsInEnv)))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

fun getVersionProps(propName: String): String {
    val propsFile = rootProject.file("version.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

android {
    namespace = "io.nekohasekai.sfa"
    compileSdk = 37
    compileSdkMinor = 1

    ndkVersion = "28.0.13004108"

    System.getenv("ANDROID_NDK_HOME")?.let { ndkPath = it }

    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "${projectDir}/schemas")
    }

    defaultConfig {
        applicationId = "io.fkbox.app"
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = getVersionProps("VERSION_CODE").toInt()
        versionName = getVersionProps("VERSION_NAME")
        base.archivesName.set("FK-box-${versionName}")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = getProps("KEYSTORE_PASS")
            keyAlias = getProps("ALIAS_NAME")
            keyPassword = getProps("ALIAS_PASS")
        }
        // Fixed key for test builds without the release key, so each CI debug
        // APK installs over the last one. Debug-only and public on purpose.
        create("ciDebug") {
            storeFile = file("debug-ci.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "fkboxdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(if (getProps("KEYSTORE_PASS").isNotEmpty()) "release" else "ciDebug")
            // CI debug builds carry their commit, shown in About, so a test APK is identifiable.
            System.getenv("GITHUB_SHA")?.take(7)?.let { versionNameSuffix = "-$it" }
        }
        release {
            isMinifyEnabled = true
            // Drops resources R8 left unreferenced. Nothing looks resources up
            // by name (no getIdentifier); res/raw/keep.xml keeps the few the
            // shrinker cannot see referenced.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            vcsInfo.include = false
        }
        // Release code, profileable and signed with the CI debug key, for the
        // Baseline Profile generator (:baselineprofile). Only defined with
        // -PbaselineProfile=true, so regular builds keep their variants.
        if (providers.gradleProperty("baselineProfile").orNull.toBoolean()) {
            create("benchmark") {
                initWith(getByName("release"))
                signingConfig = signingConfigs.getByName("ciDebug")
                matchingFallbacks += listOf("release")
                // Unobfuscated, so the collected rules carry source names
                // that R8 maps when the release build merges the profile.
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    flavorDimensions += "vendor"
    productFlavors {
        create("play")
        create("other")
    }

    splits {
        abi {
            val arm64Only = providers.gradleProperty("arm64Only").orNull.toBoolean()
            val emulatorOnly = providers.gradleProperty("emulatorOnly").orNull.toBoolean()
            isEnable = true
            isUniversalApk = !arm64Only && !emulatorOnly
            reset()
            if (emulatorOnly) {
                include("x86_64")
            } else if (arm64Only) {
                include("arm64-v8a")
            } else {
                include("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Java library APIs on supported Android versions
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // libfkui.so: the Rust + Slint UI built from ui-slint/ by cargo-ndk.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs").get().asFile)

    lint {
        fatal += "NewApi"
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            var fileName = output.outputFileName
            fileName = fileName.replace("-release", "")
            fileName = fileName.replace("-other", "")
            output.outputFileName = fileName
        }
    }
}

/**
 * Bundled geo data for Xray and mihomo (assets/geo, see libbox.md "地理数据"):
 * the files pinned in geo-assets.properties are downloaded once into a shared
 * cache, checked against their SHA-256 sums and copied with a manifest the app
 * reads to decide whether the bundle replaces an installed file.
 */
abstract class PrepareGeoAssets : DefaultTask() {
    @get:InputFile
    abstract val pins: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val props = Properties().apply { pins.get().asFile.inputStream().use { load(it) } }
        val repository = props.getProperty("GEO_REPOSITORY")
        val commit = props.getProperty("GEO_COMMIT")
        val time = Instant.parse(props.getProperty("GEO_TIME")).toEpochMilli()
        require(Regex("[0-9a-f]{40}").matches(commit)) { "GEO_COMMIT must be a full commit SHA" }
        val cache = cacheDir.get().asFile.resolve(commit).apply { mkdirs() }
        val out = outputDir.get().asFile.resolve("geo")
        out.deleteRecursively()
        out.mkdirs()
        val entries = mutableListOf<String>()
        for (name in listOf("geoip", "geosite", "mmdb", "asn")) {
            val (source, file, sha256) = props.getProperty(name).split(":")
            val cached = cache.resolve(file)
            if (!cached.isFile || sha256Of(cached) != sha256) {
                download("https://raw.githubusercontent.com/$repository/$commit/$source", cached)
                val actual = sha256Of(cached)
                if (actual != sha256) {
                    cached.delete()
                    throw GradleException("$source from $repository@$commit has SHA-256 $actual, expected $sha256")
                }
            }
            cached.copyTo(out.resolve(file), overwrite = true)
            entries += "\"$name\":{\"file\":\"$file\",\"sha256\":\"$sha256\",\"size\":${cached.length()}}"
        }
        out.resolve("manifest.json").writeText(
            "{\"source\":\"$repository\",\"commit\":\"$commit\",\"time\":$time,\"files\":{${entries.joinToString(",")}}}\n",
        )
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, target: File) {
        var failure: Exception? = null
        repeat(3) { attempt ->
            val temp = File(target.parentFile, target.name + ".part")
            try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                try {
                    if (connection.responseCode != 200) throw GradleException("HTTP ${connection.responseCode} for $url")
                    connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
                if (!temp.renameTo(target)) throw GradleException("Unable to save ${target.name}")
                return
            } catch (error: Exception) {
                temp.delete()
                failure = error
                logger.warn("Geo asset download attempt ${attempt + 1} failed: ${error.message}")
                Thread.sleep(2_000L * (attempt + 1))
            }
        }
        throw GradleException("Unable to download $url", failure)
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<PrepareGeoAssets>("prepare${variant.name.replaceFirstChar { it.uppercase() }}GeoAssets") {
            pins.set(layout.projectDirectory.file("geo-assets.properties"))
            cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/geo-assets"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, PrepareGeoAssets::outputDir)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // libbox
    implementation(files("libs/libbox.aar"))

    // Both flavors use the same API 24+ backend.
    val lifecycleVersion = "2.11.0"
    val roomVersion = "2.8.4"

    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.6.0-android")

    // BoxService status is a MutableLiveData observed with observeForever (core API only).
    implementation("androidx.lifecycle:lifecycle-livedata-core:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("com.google.android.material:material:1.14.0")
    // Hosts the Slint UI (android-activity 0.6 pairs with GameActivity 4.4.0; no prefab).
    implementation("androidx.games:games-activity:4.4.0")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Shizuku (play and other flavors, API 24+ only)
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // libsu for ROOT package query (all flavors)
    val libsuVersion = "6.0.0"
    implementation("com.github.topjohnwu.libsu:core:$libsuVersion")
    implementation("com.github.topjohnwu.libsu:service:$libsuVersion")

    implementation("androidx.activity:activity-ktx:1.13.0")

    // QR scan and QR code (CameraX + ZXing, as at 84e71e4^; shared by both flavors)
    val cameraVersion = "1.6.1"
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("com.google.zxing:core:3.5.4")

    // Compose UI: stock Material 3 and Material icons
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    // Installs the app's Baseline Profile (src/main/baseline-prof.txt) on
    // sideloaded installs, which get no Play cloud profiles.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    // Terminal emulator
    val libghosttyVersion = "0.1.0-SNAPSHOT"
    implementation("io.github.sagernet:libghostty-android:$libghosttyVersion")
    implementation("io.github.sagernet:libghostty-android-extras:$libghosttyVersion")
}

val playCredentialsJSON = rootProject.file("service-account-credentials.json")
if (playCredentialsJSON.exists()) {
    play {
        serviceAccountCredentials.set(playCredentialsJSON)
        defaultToAppBundles.set(true)
        val version = getVersionProps("VERSION_NAME")
        track.set(
            if (version.contains("alpha") || version.contains("beta")/* || version.contains("rc")*/) {
                "beta"
            } else {
                "production"
            }
        )
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_blank-line-before-declaration" to "disabled",
                "ktlint_standard_blank-line-between-when-conditions" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
            ))
    }
    java {
        target("src/**/*.java")
        googleJavaFormat()
    }
}

// Builds the Slint UI library for arm64-v8a with cargo-ndk (Rust stable, cargo-ndk,
// ANDROID_NDK_HOME). Skip with -PskipRustUi=true when the library is already staged.
val rustUiOut = layout.buildDirectory.dir("rustJniLibs")
val buildRustUi = tasks.register<Exec>("buildRustUi") {
    val crate = rootProject.file("ui-slint")
    workingDir = crate
    inputs.dir(crate.resolve("src"))
    inputs.dir(crate.resolve("ui"))
    inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"), crate.resolve("build.rs"))
    outputs.dir(rustUiOut)
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-P", "24", "-o", rustUiOut.get().asFile.absolutePath, "build", "--release")
    onlyIf { !providers.gradleProperty("skipRustUi").orNull.toBoolean() }
}
tasks.named("preBuild") { dependsOn(buildRustUi) }
