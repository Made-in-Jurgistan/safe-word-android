# Proguard rules for Safe Word Android
# Keep JNI methods used by native dependencies
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

# Timber — strip verbose/debug/info/warn logs in release; keep error for crash reports.
# * NOTE: warn is stripped because the codebase logs operational state transitions at
#   warn level that would leak app internals (model paths, state machine transitions)
#   to anyone with logcat access on a release device.
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
}
