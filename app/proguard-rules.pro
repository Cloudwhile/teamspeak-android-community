# JNI Bridge & Native Event Callback
-keep interface com.cloudwhile.tscc.data.NativeEventCallback { *; }
-keep class com.cloudwhile.tscc.data.NativeBridge { *; }
-keepclassmembers class com.cloudwhile.tscc.data.NativeBridge { *; }

# Kotlinx Serialization DTOs & Envelopes
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.cloudwhile.tscc.data.model.**$$serializer { *; }
-keepclassmembers class com.cloudwhile.tscc.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.cloudwhile.tscc.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.cloudwhile.tscc.data.model.** { *; }

-keep,includedescriptorclasses class com.cloudwhile.tscc.data.backup.**$$serializer { *; }
-keepclassmembers class com.cloudwhile.tscc.data.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.cloudwhile.tscc.data.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.cloudwhile.tscc.data.backup.** { *; }

# Room Database Entities
-keep class * extends androidx.room.RoomDatabase
-keep class com.cloudwhile.tscc.data.db.** { *; }

# WebRTC Native JNI & Hardware Codec Reflection
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
