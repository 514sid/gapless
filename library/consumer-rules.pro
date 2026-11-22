# Media3, Coil and AndroidX ship their own consumer rules.

# Preserve WebView JS interface names
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
