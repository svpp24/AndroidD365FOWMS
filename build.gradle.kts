plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere - AGP 9 compiles Kotlin itself - but on the classpath it sets the
    // Kotlin version that built-in support uses (AGP 9.5 alone would bring 2.2.10).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.junit5) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
