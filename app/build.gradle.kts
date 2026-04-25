plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Room 2.8.4 KSP processor was compiled against kotlinx.serialization 1.8.x, which added
// typeParametersSerializers() to GeneratedSerializer. The default transitive resolution lands
// on 1.7.3 (via Kotlin BOM). Force ≥ 1.8.1 across all configurations (including ksp).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" &&
            requested.name.startsWith("kotlinx-serialization")
        ) {
            useVersion("1.8.1")
            because("Room 2.8.4 requires GeneratedSerializer.typeParametersSerializers() added in 1.8.0")
        }
    }
}

android {
    namespace = "com.safeword.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.safeword.android"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                val cmakeArgs = mutableListOf<String>().apply {
                    // Common arguments
                    add("-DANDROID_STL=c++_shared")
                    add("-DANDROID_ARM_NEON=ON")
                    add("-DONNXRUNTIME_LIB_PATH=${projectDir.absolutePath}/src/main/jniLibs/arm64-v8a/libonnxruntime.so")
                    
                    // Build type specific optimizations
                    if (project.gradle.startParameter.taskNames.any { it.contains("Release") }) {
                        // Release build optimizations
                        add("-DCMAKE_BUILD_TYPE=Release")
                        // More aggressive parallelism for release builds
                        add("-DCMAKE_JOB_POOLS=compile=6;link=3")
                    } else {
                        // Debug build settings
                        add("-DCMAKE_BUILD_TYPE=Debug")
                        add("-DCMAKE_VERBOSE_MAKEFILE=ON")
                        // Conservative parallelism for debug builds
                        add("-DCMAKE_JOB_POOLS=compile=2;link=1")
                    }
                    
                    // Common job pool settings
                    add("-DCMAKE_JOB_POOL_COMPILE=compile")
                    add("-DCMAKE_JOB_POOL_LINK=link")
                }
                
                arguments += cmakeArgs
                targets("moonshine-bridge")
            }
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
        // arm64-v8a only is intentional — see copilot-instructions.md (no emulator/ChromeOS support by design)
        disable += "ChromeOsAbiSupport"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    // Extract native libs to disk so ONNX Runtime can load .so files via dlopen.
    // Without this, libs remain inside the APK and native .so discovery fails at runtime.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // MockK's JUnit 5 transitive dependency brings duplicate META-INF/LICENSE.md
            // files from junit-jupiter. Excluded because androidTest uses JUnit 4.
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Workaround for https://github.com/google/dagger/issues/4161
// When Room + Hilt both run as KSP processors, multi-round processing causes Hilt to
// regenerate hilt_aggregated_deps classes into byRounds/2/ that duplicate the main output,
// producing javac "duplicate class" errors. No upstream fix as of Dagger 2.59.2 / KSP
// 2.1.10-1.0.31.
//
// The previous blanket deleteRecursively() of byRounds/ also destroyed unique
// hilt_aggregated_deps metadata (e.g. SafeWordApp_GeneratedInjector), causing a
// ClassCastException at runtime. This targeted version only removes files that
// already exist in the main KSP output.
afterEvaluate {
    tasks.matching { it.name.startsWith("compile") && it.name.endsWith("JavaWithJavac") }
        .configureEach {
            val kspTaskName = name
                .replace("compile", "ksp")
                .replace("JavaWithJavac", "Kotlin")
            val variant = kspTaskName.removePrefix("ksp").removeSuffix("Kotlin")
                .replaceFirstChar { it.lowercase() }
            val kspJavaDir = layout.buildDirectory.dir("generated/ksp/$variant/java")
            val byRoundsDir = kspJavaDir.map { it.dir("byRounds") }
            doFirst {
                val byRounds = byRoundsDir.get().asFile
                if (!byRounds.exists()) return@doFirst
                val mainDir = kspJavaDir.get().asFile
                byRounds.walkTopDown()
                    .filter { it.isFile }
                    .forEach { byRoundFile ->
                        // Resolve the relative path inside byRounds/N/...
                        val relInByRounds = byRoundFile.relativeTo(byRounds)
                        // Strip the leading round number directory (e.g. "2/")
                        val subParts = relInByRounds.toPath().drop(1)
                        if (subParts.isNotEmpty()) {
                            val relInMain = subParts.reduce { acc, p -> acc.resolve(p) }
                            val mainFile = mainDir.resolve(relInMain.toString())
                            if (mainFile.exists()) {
                                // True duplicate — safe to delete the byRounds copy
                                byRoundFile.delete()
                            }
                        }
                    }
                // Clean up empty directories
                byRounds.walkBottomUp()
                    .filter { it.isDirectory && it.listFiles()?.isEmpty() == true }
                    .forEach { it.delete() }
            }
        }
}

dependencies {
    // Jetpack Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.56")
    ksp("com.google.dagger:hilt-android-compiler:2.56")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // WorkManager (background model downloads)
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Room Database with SQLCipher for encryption
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    ksp("androidx.room:room-compiler:2.8.4")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON (kotlinx.serialization JSON element API; pin matches Room 2.8.4 transitive resolution)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // OkHttp (model downloads)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ONNX Runtime (Silero VAD)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.0")

    // SymSpell general spell-correction (Layer 5 in ConfusionSetCorrector)
    implementation("com.darkrockstudios:SymSpellKt-android:3.4.0")
    // Double Metaphone phonetic algorithm (PhoneticIndex Layer 4 in ConfusionSetCorrector)
    implementation("commons-codec:commons-codec:1.18.0")

    // Lifecycle Compose (collectAsStateWithLifecycle)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Media3 ExoPlayer (splash video playback)
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.7.0")

    // Android instrumented tests (on-device)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    androidTestImplementation("io.mockk:mockk-android:1.14.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
