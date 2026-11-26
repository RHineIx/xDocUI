package ix.xdocui

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
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
        private const val DOCUMENTS_APPLICATION_CLASS = "com.android.documentsui.DocumentsApplication"
        private const val SORT_DIMENSION_CLASS = "com.android.documentsui.sorting.SortDimension"

        @Volatile
        private var appContext: Context? = null

        private const val PREF_SORT_LABEL = "sort_label_entry"
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
            val appClass = param.classLoader.loadClass(DOCUMENTS_APPLICATION_CLASS)
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")

            hook(onCreateMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (appContext == null) {
                        appContext = chain.thisObject as? Context
                    }
                    return chain.proceed()
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
        runSafe("persistentSort/install") {
            val model = param.classLoader.loadClass(SORT_MODEL_CLASS)
            val dimension = param.classLoader.loadClass(SORT_DIMENSION_CLASS)
            val intType = Int::class.javaPrimitiveType!!
            val sortByUser = model.checkedMethod("sortByUser", Void.TYPE, intType, intType)
            val sortOnDefault = model.checkedMethod("sortOnDefault", Void.TYPE)
            val sortByDimension = model.checkedMethod("sortByDimension", Void.TYPE, dimension, intType)
            val getSortedDimension = model.checkedMethod("getSortedDimension", dimension)
            val getSize = model.checkedMethod("getSize", intType)
            val getDimensionAt = model.checkedMethod("getDimensionAt", dimension, intType)
            val getId = dimension.checkedMethod("getId", intType)
            val getLabel = dimension.checkedMethod("getLabelId", intType)
            val getDirection = dimension.checkedMethod("getSortDirection", intType)
            val getCapability = dimension.checkedMethod("getSortCapability", intType)
            val getVisibility = dimension.checkedMethod("getVisibility", intType)
            val userSpecified = model.getDeclaredField("mIsUserSpecified").apply {
                require(type == Boolean::class.javaPrimitiveType)
                isAccessible = true
            }

            fun labelOf(context: Context, item: Any): String? {
                val id = getLabel.invoke(item) as Int
                return try {
                    context.resources.getResourceEntryName(id)
                } catch (_: android.content.res.Resources.NotFoundException) {
                    null
                }
            }

            // Capture only an actual, successful user action, never a cursor refresh.
            hook(sortByUser).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    runSafe("persistentSort/save") {
                        val instance = chain.thisObject ?: return@runSafe
                        val context = appContext ?: return@runSafe
                        val requestedId = chain.args.getOrNull(0) as? Int ?: return@runSafe
                        val requestedDirection = chain.args.getOrNull(1) as? Int ?: return@runSafe
                        if (!validDirection(requestedDirection)) return@runSafe
                        val current = getSortedDimension.invoke(instance) ?: return@runSafe
                        if (getId.invoke(current) != requestedId ||
                            getDirection.invoke(current) != requestedDirection ||
                            ((getCapability.invoke(current) as Int) and requestedDirection) == 0) {
                            return@runSafe
                        }
                        val label = labelOf(context, current) ?: return@runSafe
                        val prefs = context.getSharedPreferences("xDocUI_SortPrefs", Context.MODE_PRIVATE)
                        if (prefs.getString(PREF_SORT_LABEL, null) != label ||
                            prefs.getInt(PREF_SORT_DIRECTION, 0) != requestedDirection) {
                            prefs.edit()
                                .putString(PREF_SORT_LABEL, label)
                                .putInt(PREF_SORT_DIRECTION, requestedDirection)
                                .remove("sort_position")
                                .remove("sort_dimension_id")
                                .apply()
                        }
                    }
                    return result
                }
            })
            deoptimize(sortByUser)

            // Resolve the current resource label to its actual dimension and use
            // DocumentsUI's native sorting transition, including listener updates.
            hook(sortOnDefault).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    var selected: Any? = null
                    var direction = 0
                    val instance = chain.thisObject ?: return chain.proceed()
                    runSafe("persistentSort/resolve") {
                        val context = appContext ?: return@runSafe
                        if (userSpecified.getBoolean(instance)) return@runSafe
                        val prefs = context.getSharedPreferences("xDocUI_SortPrefs", Context.MODE_PRIVATE)
                        val label = prefs.getString(PREF_SORT_LABEL, null) ?: return@runSafe
                        val savedDirection = prefs.getInt(PREF_SORT_DIRECTION, 0)
                        if (!validDirection(savedDirection)) return@runSafe
                        val count = getSize.invoke(instance) as Int
                        if (count !in 1..128) return@runSafe
                        val matches = (0 until count).mapNotNull { getDimensionAt.invoke(instance, it) }
                            .filter { labelOf(context, it) == label }
                        val target = matches.singleOrNull() ?: return@runSafe
                        if ((getVisibility.invoke(target) as Int) != android.view.View.VISIBLE ||
                            ((getCapability.invoke(target) as Int) and savedDirection) == 0) {
                            return@runSafe
                        }
                        direction = savedDirection
                        selected = target
                    }
                    val target = selected ?: return chain.proceed()
                    // Do not retry the original operation if the native method
                    // itself throws after mutating state or notifying listeners.
                    try {
                        sortByDimension.invoke(instance, target, direction)
                    } catch (error: InvocationTargetException) {
                        throw (error.cause ?: error)
                    } catch (_: IllegalAccessException) {
                        return chain.proceed()
                    }
                    return null
                }
            })
            deoptimize(sortOnDefault)
            log(Log.INFO, "xDocUI", "Persistent sorting hooks installed (native model transitions)")
        }
    }

    private fun Class<*>.checkedMethod(
        name: String,
        returnType: Class<*>,
        vararg parameters: Class<*>,
    ): Method = getDeclaredMethod(name, *parameters).apply {
        require(this.returnType == returnType) { "Unexpected return type for $name" }
        require(!java.lang.reflect.Modifier.isStatic(modifiers)) { "Unexpected static method: $name" }
        isAccessible = true
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
