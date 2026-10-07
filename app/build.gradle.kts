import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Release signing is read from keystore.properties (git-ignored). When the
// file is absent the release build falls back to the debug key so local
// `assembleRelease` still works; CI/production must supply a real keystore.
// Release signing is read from keystore.properties (git-ignored). The location
// can be overridden with -PkeystorePropertiesFile=... or the
// KEYSTORE_PROPERTIES_FILE environment variable, so CI can point at a secret
// path without putting anything in the repository.
//
// When no keystore is configured the release build falls back to the debug key
// and prints a warning (local testing only — never for distribution).
val keystorePropertiesFile: File? =
    run {
        val override =
            (findProperty("keystorePropertiesFile") as String?)
                ?: System.getenv("KEYSTORE_PROPERTIES_FILE")
        val f = if (override != null) file(override) else rootProject.file("keystore.properties")
        f.takeIf { it.exists() }
    }

val keystoreProperties =
    Properties().apply {
        keystorePropertiesFile?.inputStream()?.use { load(it) }
    }

android {
    namespace = "com.opencode.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                // Relative storeFile values resolve against the properties file's
                // directory, so the keystore can live outside the repo.
                val base = keystorePropertiesFile?.parentFile ?: rootProject.projectDir
                storeFile = base.resolve(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate + strip resources for a production APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // -PdebugSigning produces an R8-optimised build signed with the
            // debug key, so it installs as an UPDATE over an existing debug
            // install (same signature) instead of requiring an uninstall.
            // Useful for on-device performance testing; never for distribution.
            signingConfig =
                if (!project.hasProperty("debugSigning") &&
                    keystoreProperties.getProperty("storeFile") != null
                ) {
                    signingConfigs.getByName("release")
                } else {
                    if (!project.hasProperty("debugSigning")) {
                        // Play Store readiness: a release build must never be
                        // silently debug-signed and then distributed. Warn loudly
                        // (and keep the escape hatch explicit via -PdebugSigning).
                        logger.warn(
                            "OpenCode: release has no keystore.properties — signing with the " +
                                "DEBUG key. Do NOT distribute this build; supply keystore.properties " +
                                "or pass -PdebugSigning for an intentional local build.",
                        )
                    }
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Android framework calls (Log, Base64) return defaults instead of
            // throwing "not mocked", so plain JVM tests can drive ApiClient.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Lint must fail the build on an error (e.g. an API-level violation that
        // would crash on the minimum supported Android), in debug and in release.
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Dependency-freshness noise is owned by Dependabot, not by lint.
        disable += "GradleDependency"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Compose compiler diagnostics: skippability/stability reports + metrics.
// They make recomposition regressions visible (which composables are
// restartable/skippable and which classes force recomposition). Output:
//   app/build/compose_reports/   (per-module .txt)
//   app/build/compose_metrics/   (per-composable metrics)
composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
    metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
}

dependencies {
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    // Core
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // collectAsStateWithLifecycle — stops collection while backgrounded.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Home-screen widget (Glance). Kept on the stable 1.1.x line so it matches
    // the app's Compose BOM generation.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Real HTTP against a local server: exercises the streamed JSON decode and
    // the OOM/timeout paths, which pure-function tests cannot reach.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // The Compose BOM must be applied per-configuration, otherwise the
    // androidTest/debug Compose artefacts resolve without a version.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
