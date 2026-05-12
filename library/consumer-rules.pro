# Preserve ExoPlayer classes referenced reflectively
-keep class androidx.media3.** { *; }

# Preserve WebView JS interface names
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
