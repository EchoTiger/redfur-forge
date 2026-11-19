# Preserve debuggable stack traces in release builds
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,InnerClasses,Signature

# Native / JNI media & extractor bridges
-keep class com.yausername.** { *; }
-keep class com.homesoft.photo.libraw.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**

# Room entities, DAOs, and generated implementations
-keep class com.fissal.forge.data.local.** { *; }

# kotlinx.serialization models & generated serializers
-keep class com.fissal.forge.domain.model.** { *; }
-keep class com.fissal.forge.auth.NetscapeCookieRecord** { *; }
-keep class com.fissal.forge.auth.SiteAuthVault$** { *; }
-keep,includedescriptorclasses class com.fissal.forge.**$$serializer { *; }
-keepclassmembers class com.fissal.forge.** {
    *** Companion;
}
-keepclasseswithmembers class com.fissal.forge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
