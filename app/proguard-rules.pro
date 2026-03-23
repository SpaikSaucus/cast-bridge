# CastBridge ProGuard rules

# CastOptionsProvider - referenced by class name in AndroidManifest.xml
-keep class com.castbridge.app.CastOptionsProvider { *; }

# JsBridgeInterface - methods called from JavaScript via @JavascriptInterface
-keepclassmembers class com.castbridge.app.VideoDetector$JsBridgeInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# androidx.webkit - accessed via reflection in setupBrowserWebView
-keep class androidx.webkit.WebViewCompat { *; }
-keep class androidx.webkit.WebViewFeature { *; }

# ProxyService - foreground service referenced in AndroidManifest.xml
-keep class com.castbridge.app.ProxyService { *; }

# Google Cast Framework
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
