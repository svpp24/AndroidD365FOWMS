import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.junit5)
    alias(libs.plugins.baselineprofile)
}

/** Secrets live in local.properties (dev machines) or CI env vars. Never in VCS. */
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
/**
 * A key present but blank counts as absent. local.properties ships as a template with every
 * key listed and empty, so treating "" as a real value silently defeats every default and
 * produces config that looks populated but is not.
 */
fun secret(key: String, default: String = ""): String =
    secrets.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: default

android {
    namespace = "com.d365fo.mobileops"
    compileSdk = 35

    defaultConfig {
        // Ship ARM only. The x86 and x86_64 slices exist for emulators and account for 18.7 MB
        // of native code - roughly a third of the apk - that no handheld will ever load. The
        // target device reports "armeabi-v7a,armeabi"; arm64 stays for newer hardware.
        //
        // This is the single largest size win available, and it costs nothing except the ability
        // to run on an x86 emulator, which this project cannot do anyway: VT-x is disabled in
        // the development machine's firmware, so every test has run on real hardware.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        applicationId = "com.d365fo.mobileops"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Azure AD
        buildConfigField("String", "AAD_TENANT_ID", "\"${secret("AAD_TENANT_ID")}\"")
        buildConfigField("String", "AAD_CLIENT_ID", "\"${secret("AAD_CLIENT_ID")}\"")
        // Restored after device-code-only auth proved too operationally painful to run with
        // day to day (admin consent, the public-client-flow toggle, slow AAD polling). Back to
        // compiling in a device-identity client secret, same as before that removal.
        buildConfigField("String", "AAD_CLIENT_SECRET", "\"${secret("AAD_CLIENT_SECRET")}\"")

        // Certificate pins (applied to whichever AOS host is active)
        buildConfigField("String", "CERT_PIN_PRIMARY", "\"${secret("D365_CERT_PIN_PRIMARY")}\"")
        buildConfigField("String", "CERT_PIN_BACKUP", "\"${secret("D365_CERT_PIN_BACKUP")}\"")

        // Environment resources — the active one is chosen at runtime (Dev/UAT/Prod switch)
        buildConfigField("String", "D365_COMPANY", "\"${secret("D365_COMPANY")}\"")
        buildConfigField("String", "D365_DEV_RESOURCE", "\"${secret("D365_DEV_RESOURCE")}\"")
        buildConfigField("String", "D365_UAT_RESOURCE", "\"${secret("D365_UAT_RESOURCE")}\"")
        buildConfigField("String", "D365_PROD_RESOURCE", "\"${secret("D365_PROD_RESOURCE")}\"")

        // Offline licensing (certificate-chain design) — see LicenseRootTrustAnchor.kt and
        // licensing/wms_license_tool.py. The root key is public by design (it only ever
        // verifies); RELEASE_SIGNING_CERT_SHA256 is what the app's own startup self-check
        // compares its installed signature against — see D365MobileApp.onCreate.
        buildConfigField("String", "LICENSE_ROOT_PUBLIC_KEY_B64", "\"${secret("LICENSE_ROOT_PUBLIC_KEY_B64")}\"")
        buildConfigField("String", "RELEASE_SIGNING_CERT_SHA256", "\"${secret("RELEASE_SIGNING_CERT_SHA256")}\"")

        // Inventory count extensions - each off until its X++ side exists. See FeatureFlags.kt.
        listOf(
            "FEATURE_COUNT_CLAIMS",
            "FEATURE_COUNT_DIRECT",
            "FEATURE_COUNT_LOCATION_FIRST",
            "FEATURE_COUNT_EXCEPTIONS",
            "FEATURE_COUNT_SUPERVISOR",
            "FEATURE_COUNT_SPOT",
            "FEATURE_COUNT_SPOT_WORKERS",
            "FEATURE_COUNT_OPPORTUNITY",
            "FEATURE_COUNT_DISCREPANCY",
            "FEATURE_COUNT_TRIGGERED_ALERTS",
            "FEATURE_COUNT_LOCATION_TYPES",
            "FEATURE_COUNT_EXTERNAL_COUNTERS",
            "FEATURE_COUNT_ACCURACY",
        ).forEach { flag ->
            buildConfigField("boolean", flag, secret(flag, "false").toBoolean().toString())
        }

        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        create("release") {
            storeFile = secret("RELEASE_STORE_FILE").takeIf { it.isNotBlank() }?.let { file(it) }
            storePassword = secret("RELEASE_STORE_PASSWORD")
            keyAlias = secret("RELEASE_KEY_ALIAS")
            keyPassword = secret("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "ALLOW_SENSITIVE_LOGGING", "true")
            // Pinning off in debug so Charles/Fiddler can be used against sandboxes.
            buildConfigField("boolean", "CERT_PINNING_ENABLED", "false")
            // Development only: skip the sign-in screen (WorkerSessionRepository.restoreDebugSession).
            buildConfigField("boolean", "DEV_SKIP_SIGN_IN", secret("DEV_SKIP_SIGN_IN", "false").toBoolean().toString())
            listOf("DEV_USER_NAME", "DEV_DISPLAY_NAME", "DEV_WAREHOUSE_ID", "DEV_SITE_ID").forEach { key ->
                buildConfigField("String", key, "\"${secret(key)}\"")
            }
            buildConfigField("boolean", "DEV_SUPERVISOR", secret("DEV_SUPERVISOR", "false").toBoolean().toString())
        }
        release {
            // Never skips sign-in, whatever local.properties says.
            buildConfigField("boolean", "DEV_SKIP_SIGN_IN", "false")
            listOf("DEV_USER_NAME", "DEV_DISPLAY_NAME", "DEV_WAREHOUSE_ID", "DEV_SITE_ID").forEach { key ->
                buildConfigField("String", key, "\"\"")
            }
            buildConfigField("boolean", "DEV_SUPERVISOR", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_SENSITIVE_LOGGING", "false")
            buildConfigField("boolean", "CERT_PINNING_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Applies the baseline profile bundled in the APK. Without it the profile ships but is
    // never installed, and the app starts exactly as slowly as before.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)

    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.security.crypto)
    implementation(libs.sqlcipher)
    implementation(libs.sqlite.ktx)

    implementation(libs.mlkit.barcode)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.timber)

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    // Robolectric needs the JUnit4 runner; the vintage engine lets it run on the
    // JUnit Platform alongside the Jupiter tests.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit5.vintage)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.mockk.android)
}
