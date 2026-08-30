import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val localGeminiApiKey = providers.gradleProperty("GEMINI_API_KEY")
    .orElse(providers.provider { localProperties.getProperty("GEMINI_API_KEY", "") })
val escapedGeminiApiKey = localGeminiApiKey.get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val localVoiceboxBaseUrl = providers.gradleProperty("VOICEBOX_BASE_URL")
    .orElse(providers.provider { localProperties.getProperty("VOICEBOX_BASE_URL", "") })
val escapedVoiceboxBaseUrl = localVoiceboxBaseUrl.get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val useFakeServices = providers.gradleProperty("INPREP_USE_FAKE_SERVICES")
    .orElse(providers.provider { localProperties.getProperty("INPREP_USE_FAKE_SERVICES", "false") })
    .map { it.toBooleanStrictOrNull() ?: false }
    .getOrElse(false)

android {
    namespace = "com.harsraj.inprep"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.harsraj.inprep"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.0-rc1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // DEVELOPMENT ONLY: values in BuildConfig are extractable from the APK.
            // Production Gemini traffic must use a secret-preserving backend proxy.
            buildConfigField("String", "GEMINI_API_KEY", "\"$escapedGeminiApiKey\"")
            buildConfigField("String", "VOICEBOX_BASE_URL", "\"$escapedVoiceboxBaseUrl\"")
            buildConfigField("boolean", "USE_FAKE_SERVICES", useFakeServices.toString())
        }
        release {
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "VOICEBOX_BASE_URL", "\"\"")
            buildConfigField("boolean", "USE_FAKE_SERVICES", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        sarifReport = true
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
}
