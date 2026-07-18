# rage-native consumer ProGuard rules — keep JNI symbols for the Kotlin bridge.
-keep class com.apex.rage.nativelib.RageNative { *; }
-keep class com.apex.rage.nativelib.NativeCallbacks { *; }
