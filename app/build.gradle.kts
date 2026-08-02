plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom(rootProject.file(".detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

android {
    namespace = "com.safeword.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.safeword.android"
        // The Moonshine Voice SDK (0.0.59) AAR declares minSdk=35; the manifest
        // <uses-sdk tools:overrideLibrary="ai.moonshine.voice"/> entry forces it
        // to 33 because Moonshine's API surface is API-31+ compatible (only its
        // packaging metadata is pinned to 35). 33 is the lowest stable
        // microphone-FGS-typed level that still benefits from the runtime
        // permission/notification fixes Safe Word relies on.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "com.safeword.android.HiltTestRunner"

        ndk {
            // Moonshine SDK 0.0.59 ships arm64-v8a only; Safe Word targets
            // physical Android devices, not emulators.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        // OldTargetApi: targetSdk = 35 is the current stable API level.
        // GradleDependency: dependency version policing handled separately, not in lint.
        // IconLauncherShape / MonochromeLauncherIcon: the adaptive XML icon provides monochrome,
        //   while the launcher PNG resources remain required for density-specific devices.
        // ChromeOsAbiSupport: app ships only arm64 NDK binaries (Moonshine SDK constraint).
        disable += setOf(
            "OldTargetApi",
            "GradleDependency",
            "IconLauncherShape",
            "ChromeOsAbiSupport",
            // mipmap-anydpi-v26 is required for AAPT to resolve the adaptive launcher icon;
            // removing the qualifier breaks resource resolution.
            "ObsoleteSdkInt",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Moonshine SDK and any transitive ORT consumer can each ship the same
    // libonnxruntime.so. Without a deterministic pick AGP fails the merge in
    // future versions; pickFirst keeps the first occurrence (the SDK's).
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/arm64-v8a/libonnxruntime4j_jni.so",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Jetpack Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager (background model downloads)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Room (SQLite for history)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Moonshine Voice SDK (on-device streaming STT)
    implementation("ai.moonshine:moonshine-voice:0.0.59")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Serialization (custom voice commands JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OkHttp (model downloads)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lifecycle Compose (collectAsStateWithLifecycle)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Media3 ExoPlayer (splash video playback)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Testing — kotlin("test-junit") aligns to the kotlin.android plugin
    // version declared in the root project, eliminating the second 2.1.10 pin.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("app.cash.turbine:turbine:1.1.0")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.work:work-testing:2.10.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60.1")
    androidTestImplementation("io.mockk:mockk-android:1.13.12")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.60.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
