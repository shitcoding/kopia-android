# Consumer ProGuard rules for KopiaKt Android library
# These rules will be included when the library is used as a dependency

# Keep serializable classes
-keepattributes *Annotation*
-keep class org.kopiaKt.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations
-keep,includedescriptorclasses class org.kopiaKt.**$$serializer { *; }
-keepclassmembers class org.kopiaKt.** {
    *** Companion;
}
-keepclasseswithmembers class org.kopiaKt.** {
    kotlinx.serialization.KSerializer serializer(...);
}
