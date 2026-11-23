# --- Kotlin Serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.d365fo.mobileops.**$$serializer { *; }
-keepclasseswithmembers class com.d365fo.mobileops.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit -------------------------------------------------------------
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations
-keep,allowobfuscation interface com.d365fo.mobileops.data.remote.api.**

# --- Room -----------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- ML Kit (barcode scanner) ----------------------------------------------
# ML Kit builds its registrars by reflection from manifest meta-data. firebase-components 16.1.0
# keeps only the class, and R8 full mode then drops the no-arg constructor: the registrar is
# skipped silently and the camera scanner crashed with an NPE in BarcodeAnalyzer (1.5.44).
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }

# --- Offline licensing (certificate-chain design) --------------------------
# The general kotlinx.serialization rule above already globs this package, but an explicit
# block keeps the licensing payload classes self-documenting and defensive against that glob
# ever narrowing.
-keep class com.d365fo.mobileops.data.security.license.** { *; }
-keepclassmembers class com.d365fo.mobileops.data.security.license.**$$serializer { *; }


# --- Strip all logging from release builds (warehouse data must not leak) --
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
# Timber is deliberately NOT stripped: its release tree (D365MobileApp.ReleaseTree) already
# writes only warnings and errors to logcat, and the full detail goes only to the app's own
# private log (LogStore), which is what Settings > Logs shows and feedback sends to D365FO.
# Stripping Timber here left that log - and every feedback's "App log" - empty in release.
