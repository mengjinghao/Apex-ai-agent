# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 保留 Shizuku 相关类
-keep class rikka.shizuku.** { *; }

# === 热更新模块（com.apex.agent.update）===
# 保留所有 @Serializable 数据类，避免 R8 在 release 模式下移除 kotlinx.serialization 所需的合成方法
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class com.apex.agent.update.**
-keepclassmembers class com.apex.agent.update.** {
    *** Companion;
}
-keepclasseswithmembers class com.apex.agent.update.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留 UpdateState / CheckResult / UpdateError sealed class子类，避免反射枚举/类型分发失败
-keep class com.apex.agent.update.UpdateState$* { *; }
-keep class com.apex.agent.update.CheckResult$* { *; }
-keep class com.apex.agent.update.UpdateError$* { *; }
# 保留 UpdateNotifier 使用的 NotificationCompat.Builder 调用链
-keep class androidx.core.app.NotificationCompat$Builder { *; }

# 保留 Shower 相关 Binder IPC 类型，确保与 shower-server.jar 的类名保持一致
-keep class com.ai.assistance.shower.ShowerBinderContainer { *; }
-keep class com.ai.assistance.shower.IShowerService { *; }
-keep class com.ai.assistance.shower.IShowerVideoSink { *; }

# 保留自定义的 UserService 类及 AIDL 接口
-keep class com.lyneon.cytoidinfoquerier.service.FileService { *; }
-keep class com.lyneon.cytoidinfoquerier.IFileService { *; }
-keep interface com.lyneon.cytoidinfoquerier.IFileService { *; }

# 保留 ServiceConnection 和 Binder 相关方法
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留 QuickJS 反射绑定对象
-keep class com.ai.assistance.operit.core.tools.javascript.JsEngine$JsToolCallInterface { *; }

# Rules to suppress R8 warnings about missing classes
# SVG Support
--dontwarn com.caverock.androidsvg.SVG
--dontwarn com.caverock.androidsvg.SVGParseException

# Java AWT classes (not available on Android)
--dontwarn java.awt.**
--dontwarn java.awt.color.**
--dontwarn java.awt.geom.**
--dontwarn java.awt.image.**

# Image processing libraries
--dontwarn javax.imageio.**
--dontwarn javax.xml.stream.**

# Saxon XML
--dontwarn net.sf.saxon.**

# Apache Batik
--dontwarn org.apache.batik.**

# OSGi Framework
--dontwarn org.osgi.framework.**

# XZ compression
--dontwarn org.tukaani.xz.**

# POI dependencies
--dontwarn org.apache.poi.xslf.draw.**
--dontwarn org.apache.poi.xslf.usermodel.**
--dontwarn org.apache.poi.util.**

# PDF Box dependencies
--dontwarn org.apache.pdfbox.**
--dontwarn org.apache.fontbox.**

# Apache commons compress
--dontwarn org.apache.commons.compress.archivers.sevenz.**

# xmlbeans
--dontwarn org.apache.xmlbeans.**

# GIF handling
--dontwarn pl.droidsonroids.gif.**

# Reactor BlockHound integration with Netty
--dontwarn reactor.blockhound.integration.BlockHoundIntegration
--dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.apex.**$$serializer { *; }
-keepclassmembers class com.apex.** { *** Companion; }
-keepclasseswithmembers class com.apex.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep OkHttp
--dontwarn okhttp3.**
--dontwarn okio.**

# Keep ObjectBox
-keep class io.objectbox.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Gson/Reflection used classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Coil
-keep class coil.** { *; }
--dontwarn coil.**

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <fields>;
    @com.squareup.moshi.ToJson <fields>;
}

# DataStore
-keep class androidx.datastore.** { *; }

# Navigation Compose
-keep class androidx.navigation.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Apache POI
-keep class org.apache.poi.** { *; }
--dontwarn org.apache.poi.**

# PDFBox
-keep class org.apache.pdfbox.** { *; }
--dontwarn org.apache.pdfbox.**

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }

# libsu
-keep class com.topjohnwu.superuser.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }
--dontwarn org.bouncycastle.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
# Fix R8 missing class
-dontwarn java.lang.invoke.StringConcatFactory
