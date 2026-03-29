# Proguard rules for Safe Word Android
# Keep JNI methods for whisper.cpp
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class com.safeword.android.data.db.** { *; }

# OkHttp — suppress warnings for optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Timber — strip verbose/debug logs in release
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
}
