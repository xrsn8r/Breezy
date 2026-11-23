# JNI: native methods are looked up by name.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class dev.breezy.browser.adblock.NativeShield { *; }

# JavaScript bridge methods are called by name from page scripts.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
