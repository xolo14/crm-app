# Syncpedia CRM ProGuard rules

# App models — never obfuscate (Gson/Retrofit need field names)
-keep class com.syncpedia.crm.models.** { *; }

# Retrofit + OkHttp
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# R8 full mode drops generic signatures from anything not explicitly kept, which breaks
# Retrofit's parsing of suspend functions returning Response<Foo<Bar>> ("Class cannot be
# cast to ParameterizedType"). Keep the API interfaces and the generic carrier types.
-keep interface com.syncpedia.crm.network.ApiService { *; }
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Gson
-keep class com.google.gson.** { *; }

# Google Sign-In credential
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**

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
