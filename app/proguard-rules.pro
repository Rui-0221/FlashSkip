# Keep AccessibilityService entrypoint and pure-Kotlin core models
-keep class com.flashskip.SkipAdService { *; }
-keep class com.flashskip.core.** { *; }
-keep class com.flashskip.experimental.UiAutomationRunner {
    public <init>(android.content.Context);
}
