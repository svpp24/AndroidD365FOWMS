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

# --- SQLCipher ------------------------------------------------------------
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# --- MSAL -----------------------------------------------------------------
-keep class com.microsoft.identity.** { *; }
-keep class com.microsoft.aad.** { *; }
-dontwarn com.microsoft.identity.**

# --- Strip all logging from release builds (warehouse data must not leak) --
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
