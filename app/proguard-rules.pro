# R8 / ProGuard rules for the release build.
#
# Most third-party libraries ship their own consumer rules (Hilt, Retrofit,
# OkHttp, Compose). The rules below cover the reflection the app itself relies
# on, so a minified release build behaves like the debug build.

# ---------------------------------------------------------------------------
# kotlinx.serialization — keep generated serializers and companions.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep every @Serializable model in this app and its generated serializer.
-keep,includedescriptorclasses class com.opencode.android.**$$serializer { *; }
-keepclassmembers class com.opencode.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.opencode.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Retrofit — the interface is used via reflection; keep signatures and the
# generic information Retrofit reads to build suspend-function call adapters.
# ---------------------------------------------------------------------------
-keepattributes Signature, Exceptions, *Annotation*
-keep,allowobfuscation interface com.opencode.android.data.OpenCodeV2Api
-keep,allowobfuscation interface com.opencode.android.data.OpenCodeApi

# Retrofit/DTO generic types are read at runtime.
-keep class com.opencode.android.domain.** { *; }
-keep class com.opencode.android.data.WireEnvelope { *; }

# ---------------------------------------------------------------------------
# OkHttp / Okio — platform warnings that are safe to ignore.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Coroutines — the debug agent/instrumentation classes are not needed.
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
