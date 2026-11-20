plugins {
    id("com.android.application")
}

android {
    namespace = "io.fkbox.kernelprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.fkbox.kernelprobe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "p0"
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
    // Executables shipped as lib*.so must be extracted to nativeLibraryDir,
    // the only app location Android 10+ lets an app execute from.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    sourceSets["main"].jniLibs.srcDir("jniLibs")
}
