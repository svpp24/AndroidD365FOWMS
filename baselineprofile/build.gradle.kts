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
