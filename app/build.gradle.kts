plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.gallerydl"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.gallerydl"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
  implementation("androidx.documentfile:documentfile:1.0.1")
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

