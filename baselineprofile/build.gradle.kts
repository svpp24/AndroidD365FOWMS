plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.d365fo.mobileops.baselineprofile"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        // 28 to match the app: the handhelds in the fleet are API 28, and a profile generated
        // against a higher minimum can reference classes that do not exist there.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The benchmarks (StartupBenchmark, DemoFlowBenchmark) drive the app's macrobench build: the
    // baselineprofile plugin derives a profileable `benchmarkMacrobench` variant from it, so
    // ./gradlew :baselineprofile:connectedBenchmarkMacrobenchAndroidTest - or
    // tools/perf/run-benchmarks.ps1, which also pulls the results. The plugin only derives
    // variants for build types this module has too, hence this one.
    buildTypes {
        create("macrobench") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }
    targetProjectPath = ":app"
}

// Generation needs a rooted device or an AOSP/google_apis emulator image — a Play Store image
// cannot be rooted, and `user` builds like the P2mini handhelds cannot dump their own profile.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
