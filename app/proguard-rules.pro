# 1. Preserve the Xposed entry point class and all its members for the new package.
-keep class ix.xdocui.XposedInit {
    *;
}

# 2. Prevent R8/ProGuard from throwing warnings about the Xposed framework.
-dontwarn de.robv.android.xposed.**

# 3. Standard optimizations for a headless Kotlin app to ensure minimum APK size
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
