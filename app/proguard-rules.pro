# Proguard rules for Safe Word Android
# Keep JNI methods for ONNX Runtime and Moonshine native bridge
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep MoonshineNativeBridge — native method names must not be renamed
-keep class com.safeword.android.transcription.MoonshineNativeBridge { *; }

# Keep Room entities — only fields annotated with @ColumnInfo / @PrimaryKey.
# Room accesses these via reflection; unannotated helper fields can be renamed.
-keep @androidx.room.Entity class * {
    @androidx.room.ColumnInfo <fields>;
    @androidx.room.PrimaryKey <fields>;
}
# Keep Room DAO interfaces (KSP-generated implementations reference interface members by name)
-keep @androidx.room.Dao interface * { *; }
# Keep Room database class
-keep class * extends androidx.room.RoomDatabase { *; }

# OkHttp — keep CertificatePinner (uses reflection for pin verification)
-keepnames class okhttp3.internal.tls.CertificateChainCleaner

# OkHttp — suppress warnings for optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Timber — strip verbose/debug logs in release; keep info/warn/error for production observability
# INIT/LIFECYCLE/STATE/PERF/EXIT all use Timber.i — stripping them would remove all crash context.
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
}
