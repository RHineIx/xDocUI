package ix.xdocui

import android.content.Context
import android.database.Cursor
import android.util.Log
import android.util.SparseArray
import java.util.concurrent.ConcurrentHashMap
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

        private const val PREF_SORT_ID = "sort_dimension_id"
        private const val PREF_SORT_DIRECTION = "sort_direction"
        private const val SORT_ASCENDING = 1
        private const val SORT_DESCENDING = 2
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        
        // Ensure framework compatibility with API 102 requirements
        if (apiVersion < XposedInterface.API_102) {
            log(Log.WARN, "xDocUI", "This module requires libxposed API 102 or higher.")
        }
    }

    // A fresh process is required until hook replacement is implemented for API 102.
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = false

    private val reportedFailures = ConcurrentHashMap.newKeySet<String>()

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

            // Resolve the fields once rather than searching the hierarchy on every cursor.
            val userSpecifiedField = sortModelClass.getDeclaredField("mIsUserSpecified").apply { isAccessible = true }
            val dimensionsField = sortModelClass.getDeclaredField("mDimensions").apply { isAccessible = true }
            val sortedField = sortModelClass.getDeclaredField("mSortedDimension").apply { isAccessible = true }
            val directionField = sortedField.type.getDeclaredField("mSortDirection").apply { isAccessible = true }
            val capabilityField = sortedField.type.getDeclaredField("mSortCapability").apply { isAccessible = true }

            hook(sortCursorMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    runSafe("sortCursor/runtime") {
                        val model = chain.thisObject ?: return@runSafe
                        val context = appContext ?: return@runSafe
                        val prefs = context.getSharedPreferences("xDocUI_SortPrefs", Context.MODE_PRIVATE)
                        val dimensions = dimensionsField.get(model) as? SparseArray<*> ?: return@runSafe
                        if (userSpecifiedField.getBoolean(model)) {
                            val current = sortedField.get(model) ?: return@runSafe
                            val direction = directionField.getInt(current)
                            if (!validDirection(direction)) return@runSafe
                            val index = (0 until dimensions.size()).firstOrNull {
                                dimensions.valueAt(it) === current
                            } ?: return@runSafe
                            if ((capabilityField.getInt(current) and direction) == 0) return@runSafe
                            val id = dimensions.keyAt(index)
                            if (prefs.getInt(PREF_SORT_ID, Int.MIN_VALUE) != id ||
                                prefs.getInt(PREF_SORT_DIRECTION, 0) != direction) {
                                prefs.edit()
                                    .putInt(PREF_SORT_ID, id)
                                    .putInt(PREF_SORT_DIRECTION, direction)
                                    .remove("sort_position")
                                    .apply()
                            }
                            userSpecifiedField.setBoolean(model, false)
                        } else {
                            // Ignore legacy position-only data: the index is not a stable identifier.
                            if (!prefs.contains(PREF_SORT_ID)) return@runSafe
                            val direction = prefs.getInt(PREF_SORT_DIRECTION, 0)
                            if (!validDirection(direction)) return@runSafe
                            val target = dimensions.get(prefs.getInt(PREF_SORT_ID, Int.MIN_VALUE))
                                ?: return@runSafe
                            if ((capabilityField.getInt(target) and direction) == 0) return@runSafe
                            val previous = sortedField.get(model)
                            if (previous !== target) {
                                sortedField.set(model, target)
                                if (previous != null) directionField.setInt(previous, 0)
                            }
                            directionField.setInt(target, direction)
                        }
                    }
                    // Never catch or retry the original application's own cursor operation.
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

    private fun validDirection(direction: Int): Boolean =
        direction == SORT_ASCENDING || direction == SORT_DESCENDING

    private inline fun runSafe(hookName: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            if (reportedFailures.add(hookName)) {
                log(Log.WARN, "xDocUI", "$hookName failed: ${e.javaClass.simpleName}: ${e.message}")
            }
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

}
