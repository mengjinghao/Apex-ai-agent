# rage-jni ProGuard rules — keep the JNI bridge API surface.
-keep class com.apex.rage.nativelib.RageNative { *; }
-keep class com.apex.rage.nativelib.NativeCallbacks { *; }
-keep class com.apex.rage.nativelib.RageNativeBridge { *; }
-keep class com.apex.rage.nativelib.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
