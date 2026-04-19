# ProGuard rules for xDocUI

# Adapt resource file names
-adaptresourcefilenames **/*.list,**/*.prop

# Keep entry point
-keep class ix.xdocui.XposedInit { *; }

# Suppress libxposed warnings
-dontwarn io.github.libxposed.api.**