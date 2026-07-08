import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

// Optional local file at the repo root (gitignored) that holds the release
// signing credentials. If present we wire a proper release signing config;
// otherwise the release build falls back to the debug keystore so a fresh
// clone still builds without extra setup.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

// Load environment variables from .env file at the repo root
val envPropsFile = rootProject.file(".env")
val envProps = Properties().apply {
    if (envPropsFile.exists()) {
        envPropsFile.inputStream().use { load(it) }
    }
}
// Get AEMET API key from .env or use empty string as fallback
val aemetApiKey = envProps.getProperty("AEMET_API_KEY", "")

android {
    namespace = "com.example.aemet_tiempo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aemet_tiempo"
        // minSdk 23 = Android 6.0 Marshmallow. java.time APIs aren't native
        // below API 26 (Android 8.0) — we enable core-library desugaring
        // below to backport them.
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // API key loaded from .env file. The value is interpolated
        // verbatim into BuildConfig.java, so it MUST include the surrounding
        // double-quotes — otherwise the JWT's dots/dashes look like Java syntax.
        // IMPORTANT: This will be embedded inside the APK.
        buildConfigField(
            "String",
            "AEMET_API_KEY",
            "\"$aemetApiKey\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks + obfuscates Kotlin/Compose code; resource shrinking
            // strips unused drawables/strings. Both reduce APK size and start
            // time without changing behaviour.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Fallback so a fresh clone (no keystore.properties) can still
                // produce a release APK signed with the local debug key.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Allows us to use java.time.* on minSdk < 26 by shipping a
        // backport inside the APK.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // We expose the AEMET API key via BuildConfig.AEMET_API_KEY.
        // Since AGP 8.0 this feature is opt-in.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    lint {
        // Android Lint occasionally crashes inside its UAST visitor on
        // Compose-heavy code (RuntimeException in LintDriver). The check
        // still runs and reports findings, but we don't want a lint
        // crash to block a release build of a personal app.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3:1.4.0-alpha14")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // java.time.* backport for API < 26 (Android < 8.0).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

