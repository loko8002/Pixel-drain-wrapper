# Keep the JavaScript interface methods (none currently, but future-proof).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView with JS callbacks.
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Keep View Binding generated classes.
-keep class com.pixeldrain.wrapper.databinding.** { *; }
