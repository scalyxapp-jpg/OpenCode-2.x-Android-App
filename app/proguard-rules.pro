# Add project specific ProGuard rules here.
# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.opencode.android.**$$serializer { *; }
-keepclassmembers class com.opencode.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.opencode.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}