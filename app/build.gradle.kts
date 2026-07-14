import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Developer-local settings, never committed (local.properties is gitignored). Used only to let a
// DEBUG build running on an EMULATOR stand in a hosted Gemma for on-device Gemini Nano, which the
// emulator doesn't have. Absent here, the app simply runs without a generative backend.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase()) ?: default)

plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android: AGP 9 built-in Kotlin compiles the Kotlin sources.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pricefighter"
    // androidx.appfunctions 1.0.0-alpha09 requires compileSdk 37 (Android 17) and AGP 9.1.0+.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pricefighter"
        // App Functions run on API 36+ devices; we compile against 37 for the alpha09 APIs.
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            // Emulator-only Nano stand-in. Set these in local.properties (gitignored):
            //   openrouter.api.key=sk-or-...
            //   openrouter.model=google/gemma-3-27b-it
            buildConfigField("String", "OPENROUTER_API_KEY", "\"${localProp("openrouter.api.key")}\"")
            buildConfigField(
                "String",
                "OPENROUTER_MODEL",
                "\"${localProp("openrouter.model", "google/gemma-3-27b-it")}\"",
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never ship a remote stand-in: release builds have no key and no model.
            buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
            buildConfigField("String", "OPENROUTER_MODEL", "\"\"")
        }
    }

    compileOptions {
        // appfunctions ships Java 11 bytecode.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Surface println output from unit tests and forward the opt-in flags used by the
// live eBay integration drive (EbayLiveIntegrationTest).
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
    listOf("pricefighter.live", "pricefighter.term").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// The App Functions KSP processor must aggregate every @AppFunction in the module
// into a single descriptor that the OS indexes for agent (Gemini) discovery.
ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX (Camera tab preview + capture).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // On-device product identification (barcode → OCR → Gemini Nano).
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.genai.prompt)

    // Local-only persistence for the price-check history.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gemini-callable app functions (the "skill").
    implementation(libs.appfunctions)
    implementation(libs.appfunctions.service)
    ksp(libs.appfunctions.compiler)

    // Networking + HTML parsing for the eBay scrape.
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.cronet.embedded)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.jsoup)

    // On-device (instrumented) tests.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
