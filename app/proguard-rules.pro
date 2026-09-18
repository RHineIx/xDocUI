# Suppress compiler warnings for Xposed annotation packages and libxposed API
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**

# Automatically rewrite the entries in java_init.list if the classes are obfuscated by R8
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Keep the entry classes and their parameterless constructors intact for the framework to inject
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}