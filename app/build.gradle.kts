plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.comfort.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.comfort.app"
        minSdk = 24
        targetSdk = 36
        // Bump both on every release you publish to github.com/d-gibr1l/comfort — versionName is
        // what AppUpdater.kt actually compares against that repo's latest GitHub Release tag (a
        // leading "v" is stripped, e.g. tag "v1.1.0" -> "1.1.0"), so a release with a versionName
        // this app doesn't already consider newer than what's installed won't get offered as an
        // update at all. versionCode has no reader of its own in this app (it's the Play Store's
        // own install/upgrade key, not used here) but real Android tooling still expects it to move
        // in step, so bump it anyway on principle.
        versionCode = 4
        versionName = "1.1.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "android"
            keyAlias = "release"
            keyPassword = "android"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // armeabi-v7a added for real budget devices (Samsung's own A0-Core line confirmed) that
            // ship a 32-bit-only Android build despite 64-bit-capable silicon — Build.SUPPORTED_ABIS
            // reports armeabi-v7a there with no arm64-v8a at all, so without this split the app
            // installed and ran (no native-code dependency in the Kotlin/Compose UI) but every
            // native-backed feature silently had nothing to load, surfacing as PythonRuntime's
            // graceful "Failed to provision the Python runtime" rather than a crash. All four
            // native components (python/qjs/aria2c/ffmpeg) now exist for this ABI —
            // jniLibs/armeabi-v7a/lib{python,qjs,aria2c,ffmpeg}*, all from the same
            // ytdlnis-packages source. ffmpeg's own build for this ABI is dynamically linked
            // (unlike the fully-static single-binary bundled for arm64-v8a/x86_64), so it also
            // ships libffmpeg.zip.so — its actual shared-library dependencies, provisioned the
            // same zip.so-unpacking way PythonRuntime/Aria2Runtime already provision their own
            // (see FfmpegRuntime.ensureProvisioned).
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")

        }
        // Release-optimised (R8, not debuggable) but signed with the debug key, so it installs over
        // the everyday debug build as a plain update — same package, same signature, data kept —
        // for measuring real performance. Profileable via src/benchmark/AndroidManifest.xml.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by datetime-wheel-picker (kotlinx-datetime under the hood) since minSdk 24 < 26.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        // compileSdk 23+ defaults to loading native libs straight out of the APK zip
        // (extractNativeLibs=false) instead of extracting them to a real file on disk — which
        // means nativeLibraryDir ends up empty at runtime. libqjs.so (see QuickJsRuntime) needs
        // to be a real, executable file at a real path, so legacy packaging forces the old
        // extract-to-disk install behavior back on.
        useLegacyPackaging = true
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.splashscreen)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  // Pinned past the BOM's stable 1.4.0 recommendation: CircularWavyProgressIndicator (M3
  // Expressive) only exists starting in this alpha train, nothing else here depends on it.
  implementation("androidx.compose.material3:material3:1.5.0-alpha18")
  implementation("io.coil-kt:coil-compose:2.6.0")
  // Coil can't decode a preview frame from video files out of the box — without this, any video
  // thumbnail (MediaStore video Uri) just renders blank instead of falling through to an error
  // state, since coil-compose alone has no decoder that understands video content at all.
  implementation("io.coil-kt:coil-video:2.6.0")
  implementation("br.com.devsrsouza.compose.icons:feather:1.1.0")
  // Material Symbols (the real MD3 icon set) — version-less, resolved by composeBom above like
  // every other androidx.compose.* artifact here.
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.documentfile:documentfile:1.0.1")
  // Media3 ExoPlayer for Trim UI Video Streaming. media3-exoplayer-hls specifically: yt-dlp/
  // YouTube resolve some formats (confirmed live on a YouTube Shorts link) to an HLS (.m3u8)
  // manifest URL rather than a plain progressive file — without this on the classpath,
  // DefaultMediaSourceFactory has no registered factory for that content type and throws
  // IllegalStateException("No suitable media source factory found for content type: 2")
  // straight out of a LaunchedEffect, which crashed the whole app rather than just failing to
  // preview. DefaultMediaSourceFactory discovers this extension via reflection at runtime, so
  // just having it on the classpath is enough — no source-level wiring needed.
  implementation("androidx.media3:media3-exoplayer:1.2.0")
  implementation("androidx.media3:media3-exoplayer-hls:1.2.0")
  implementation("androidx.media3:media3-ui:1.2.0")

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Schedule time-window picker (wheel-style Start/End pickers, replacing the native
  // TimePickerDialog)
  implementation("io.github.darkokoa:datetime-wheel-picker:1.4.0")
  implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}



