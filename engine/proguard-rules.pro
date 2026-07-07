-keep public class * implements android.os.IInterface
-keepclassmembers class * implements android.os.IInterface {
    public *;
}

-keep class com.ai.assistance.operit.engine.** { *; }
-keep interface com.ai.assistance.operit.engine.** { *; }
-keep class com.ai.assistance.operit.engine.model.** { *; }

-keepclassmembers class * {
    @android.webkit.JavascriptInterface *;
}