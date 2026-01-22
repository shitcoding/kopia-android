# KopiaKt Android ProGuard Rules

# Keep KopiaKt model classes for serialization
-keep class org.kopiaKt.snapshot.model.** { *; }
-keep class org.kopiaKt.core.format.** { *; }
-keep class org.kopiaKt.core.manifest.** { *; }
-keep class org.kopiaKt.core.content.** { *; }
# Note: 'object' is a Kotlin keyword, but ProGuard uses the JVM class name
-keep class org.kopiaKt.core.object.** { *; }

# Keep app domain models
-keep class org.kopiaKt.app.domain.model.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializers for all @Serializable classes
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Bouncycastle crypto provider
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# SSH (SSHJ) library
-keep class net.schmizz.** { *; }
-dontwarn net.schmizz.**
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# EdDSA (used by SSHJ)
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.x509.**

# AWS SDK (for S3)
-keep class software.amazon.awssdk.** { *; }
-dontwarn software.amazon.awssdk.**

# Netty (used by various network libraries)
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn reactor.blockhound.**

# OkHttp/Sardine (for WebDAV)
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class com.github.sardine.** { *; }
-dontwarn com.github.sardine.**

# Apache HTTP (used by WebDAV libraries)
-keep class org.apache.http.** { *; }
-dontwarn org.apache.http.**

# Google Tink (crypto library) - dontwarn for error prone annotations
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# JAXB runtime (pulled by some libraries)
-dontwarn org.glassfish.jaxb.**
-dontwarn jakarta.xml.bind.**
-dontwarn javax.xml.stream.**

# Java AWT/Swing (not available on Android)
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn java.beans.**

# JNDI/LDAP (not fully available on Android)
-dontwarn javax.naming.**

# Java Modules (not available on Android)
-dontwarn java.lang.Module

# GSSAPI/Kerberos (optional auth)
-dontwarn org.ietf.jgss.**

# Hilt
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.* <fields>;
}

# Compose - keep compose classes
-keep class androidx.compose.** { *; }

# Keep Kotlin metadata for reflection
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# Standard Android rules
-keepattributes Signature
-keepattributes Exceptions

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
