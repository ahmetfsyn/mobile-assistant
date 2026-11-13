plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val companionBackendUrl = providers.gradleProperty("companionBackendUrl")
    .orElse(providers.environmentVariable("COMPANION_BACKEND_URL"))
    .getOrElse("")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val phase6ManualTestDefaults = providers.gradleProperty("phase6ManualTestDefaults")
    .orElse("false")
    .get()
    .toBoolean()

android {
    namespace = "com.companion.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.companion.assistant"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Current GM 5 Plus system exposes armeabi-v7a. Keep arm64 for future 64-bit phones.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        buildConfigField("String", "COMPANION_BACKEND_URL", "\"$companionBackendUrl\"")
        buildConfigField("boolean", "PHASE6_MANUAL_TEST_DEFAULTS", phase6ManualTestDefaults.toString())
        testInstrumentationRunner = "com.companion.assistant.BackendProbeInstrumentation"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
