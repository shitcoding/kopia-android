# ProGuard rules for KopiaKt app

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# AWS SDK
-dontwarn software.amazon.awssdk.**
-keep class software.amazon.awssdk.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Ktor
-dontwarn io.ktor.**
