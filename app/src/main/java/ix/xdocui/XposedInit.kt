package ix.xdocui

import android.content.Context
import android.database.Cursor
import android.util.Log
import android.util.SparseArray
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class XposedInit : XposedModule() {

    companion object {
        private const val DOCUMENTSUI_PACKAGE = "com.android.documentsui"
        private const val DOCUMENTSUI_GOOGLE_PACKAGE = "com.google.android.documentsui"
        private const val EXTERNAL_STORAGE_PACKAGE = "com.android.externalstorage"

        private const val ACTIVITY_CONFIG_CLASS = "com.android.documentsui.ActivityConfig"
        private const val DOCUMENT_STACK_CLASS = "com.android.documentsui.base.DocumentStack"
        private const val EXTERNAL_STORAGE_PROVIDER_CLASS = "com.android.externalstorage.ExternalStorageProvider"

        private const val SORT_MODEL_CLASS = "com.android.documentsui.sorting.SortModel"
        private const val LOOKUP_CLASS = "com.android.documentsui.base.Lookup"

        @Volatile
        private var appContext: Context? = null
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        
        // Ensure framework compatibility with API 102 requirements
        if (apiVersion < XposedInterface.API_102) {
            log(Log.WARN, "xDocUI", "This module requires libxposed API 102 or higher.")
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Explicitly approve hot-reload to allow dynamic module generation replacement
        return true
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)

        // Abort execution safely if running on an unsupported older framework
        if (apiVersion < XposedInterface.API_102) return

        if (!param.isFirstPackage) return

        when (param.packageName) {
            DOCUMENTSUI_PACKAGE, DOCUMENTSUI_GOOGLE_PACKAGE -> {
                captureAppContext(param)
                hookDocumentsUI(param)
                hookRememberGlobalSort(param)
            }
            EXTERNAL_STORAGE_PACKAGE -> {
                hookExternalStorage(param)
            }
        }
    }

    private fun captureAppContext(param: XposedModuleInterface.PackageReadyParam) {
        runSafe("captureAppContext") {
            val appClass = param.classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")

            hook(onCreateMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (appContext == null) {
                        appContext = chain.thisObject as? Context
                    }
                    return result
                }
            })
            deoptimize(onCreateMethod)
        }
    }

    private fun hookDocumentsUI(param: XposedModuleInterface.PackageReadyParam) {
        runSafe("managedModeEnabled") {
            val configClass = param.classLoader.loadClass(ACTIVITY_CONFIG_CLASS)
            val docStackClass = param.classLoader.loadClass(DOCUMENT_STACK_CLASS)
            val managedModeMethod = configClass.getDeclaredMethod("managedModeEnabled", docStackClass)

            hook(managedModeMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val stack = chain.args.firstOrNull()
                    if (stack != null) {
                        val root = stack.getFieldValue("root")
                        val authority = root?.getFieldValue("authority") as? String

                        if (authority == "com.android.externalstorage.documents") {
                            return true
                        }
                    }
                    return chain.proceed()
                }
            })
            deoptimize(managedModeMethod)
        }
    }

    private fun hookRememberGlobalSort(param: XposedModuleInterface.PackageReadyParam) {
        runSafe("sortCursor") {
            val sortModelClass = param.classLoader.loadClass(SORT_MODEL_CLASS)
            val lookupClass = param.classLoader.loadClass(LOOKUP_CLASS)
            val sortCursorMethod = sortModelClass.getDeclaredMethod("sortCursor", Cursor::class.java, lookupClass)

            hook(sortCursorMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val sortModel = chain.thisObject
                    val context = appContext

                    if (context != null) {
                        val prefs = context.getSharedPreferences("xDocUI_SortPrefs", Context.MODE_PRIVATE)
                        val isUserSpecified = sortModel.getFieldValue("mIsUserSpecified") as? Boolean ?: false
                        val dimensions = sortModel.getFieldValue("mDimensions") as? SparseArray<*>

                        if (dimensions != null) {
                            if (isUserSpecified) {
                                val currentDim = sortModel.getFieldValue("mSortedDimension")
                                if (currentDim != null) {
                                    val direction = currentDim.getFieldValue("mSortDirection") as? Int ?: -1
                                    var position = -1

                                    for (i in 0 until dimensions.size()) {
                                        if (dimensions.valueAt(i) === currentDim) {
                                            position = i
                                            break
                                        }
                                    }

                                    if (position != -1) {
                                        prefs.edit()
                                            .putInt("sort_position", position)
                                            .putInt("sort_direction", direction)
                                            .apply()

                                        sortModel.setFieldValue("mIsUserSpecified", false)
                                    }
                                }
                            } else {
                                val savedPos = prefs.getInt("sort_position", -1)
                                val savedDir = prefs.getInt("sort_direction", -1)

                                if (savedPos != -1 && savedDir != -1 && savedPos < dimensions.size()) {
                                    val targetDim = dimensions.valueAt(savedPos)
                                    if (targetDim != null) {
                                        targetDim.setFieldValue("mSortDirection", savedDir)
                                        sortModel.setFieldValue("mSortedDimension", targetDim)
                                    }
                                }
                            }
                        }
                    }
                    return chain.proceed()
                }
            })
            deoptimize(sortCursorMethod)
        }
    }

    private fun hookExternalStorage(param: XposedModuleInterface.PackageReadyParam) {
        runSafe("externalStorageBypass") {
            val espClass = param.classLoader.loadClass(EXTERNAL_STORAGE_PROVIDER_CLASS)
            val methodsToHook = arrayOf("shouldBlockDirectoryFromTree", "shouldBlockFromTree", "shouldHideDocument")

            val hooker = object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return false
                }
            }

            for (methodName in methodsToHook) {
                try {
                    val method = espClass.getDeclaredMethod(methodName, String::class.java)
                    hook(method).intercept(hooker)
                    deoptimize(method)
                } catch (e: NoSuchMethodException) {
                    // Ignore missing methods gracefully
                }
            }
        }
    }

    private inline fun runSafe(hookName: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Silently fail to keep logs completely clean for production
        }
    }

    private fun Any?.getFieldValue(fieldName: String): Any? {
        if (this == null) return null
        var clazz: Class<*>? = this.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(this)
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    private fun Any?.setFieldValue(fieldName: String, value: Any?) {
        if (this == null) return
        var clazz: Class<*>? = this.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(this, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
    }
}