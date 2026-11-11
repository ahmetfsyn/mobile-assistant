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
    testImplementation("junit:junit:4.13.2")
}
