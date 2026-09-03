# Syncpedia CRM ProGuard rules

# App models — never obfuscate (Gson/Retrofit need field names)
-keep class com.syncpedia.crm.models.** { *; }

# Retrofit + OkHttp
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# Gson
-keep class com.google.gson.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# InCallService
-keep class * extends android.telecom.InCallService { *; }

# Room (if used)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Remove verbose logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Strip coroutine debug artifacts
-dontwarn kotlinx.coroutines.debug.*
-assumenosideeffects class kotlinx.coroutines.DebugKt {
    public static *** getDebugMode(...);
}
