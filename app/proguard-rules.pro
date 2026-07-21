# Preserve attributes needed by Moshi, Room, Retrofit, and Kotlin at runtime.
# Removing any of these causes silent runtime failures in reflection-based libraries.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Compose — narrow: allow obfuscation but keep classes
-keep,allowobfuscation class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep CameraX — narrow: allow obfuscation
-keep,allowobfuscation class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep Moshi + generated adapters
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keep class com.example.**JsonAdapter { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# Keep Kotlin coroutines internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Firebase — narrow: allow obfuscation
-keep,allowobfuscation class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep app data classes (Moshi reflective serialization targets)
-keep class com.example.zoom.LensProfile { *; }
-keep class com.example.zoom.LensRole { *; }
-keep class com.example.zoom.CaptureExtension { *; }
-keep class com.example.zoom.BindResult { *; }
-keep class com.example.zoom.AspectRatio { *; }
-keep class com.example.ExifData { *; }
-keep class com.example.FilmPreset { *; }
-keep class com.example.color.CubeLut { *; }
-keep class com.example.zoom.CaptureController$CaptureResult { *; }
-keep class com.example.zoom.LensCatalog$CatalogResult { *; }

# Keep Compose runtime (reflection-heavy)
-keep class androidx.compose.runtime.** { *; }

# OkHttp / Retrofit — narrow: allow obfuscation
-keep,allowobfuscation class okhttp3.** { *; }
-keep,allowobfuscation class retrofit2.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}