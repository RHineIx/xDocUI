package ix.xdocui

import android.app.Application
import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

        @Volatile
        private var appContext: Context? = null

        private const val PREF_SORT_LABEL = "sort_label_entry"
        private const val PREF_SORT_DIRECTION = "sort_direction"
        private const val SORT_PREFS = "xDocUI_SortPrefs"
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
    private val reportedEvents = ConcurrentHashMap.newKeySet<String>()

    private fun reportOnce(event: String, message: String) {
        if (reportedEvents.add(event)) log(Log.INFO, "xDocUI", message)
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
            val manifestClass = param.applicationInfo.className?.takeIf { it.isNotBlank() }
                ?: DOCUMENTS_APPLICATION_CLASS
            val appClass = param.classLoader.loadClass(manifestClass)
            require(Application::class.java.isAssignableFrom(appClass)) { "Invalid application class" }
            val onCreateMethod = appClass.checkedMethod("onCreate", Void.TYPE)

            hook(onCreateMethod).setId("sort.context").intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (appContext == null) {
                        appContext = chain.thisObject as? Context
                        if (appContext != null) reportOnce("sort/context", "Sort: application context ready")
                    }
                    return chain.proceed()
                }
            })
            reportDeoptimization("sort.context", onCreateMethod)
            reportOnce("sort/context-hook", "Sort: application context hook installed")
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
            val loader = param.classLoader
            val bindings = SortBindings.resolve(
                namedModel = optionalClass(loader, SORT_MODEL_CLASS),
                anchorModels = sequenceOf(
                    "com.android.documentsui.base.State" to "sortModel",
                    "com.android.documentsui.DirectoryLoader" to "mModel",
                    "com.android.documentsui.sorting.SortListFragment" to "mModel",
                ).mapNotNull { (owner, field) ->
                    optionalClass(loader, owner)?.let { type ->
                        try {
                            type.getDeclaredField(field).type
                        } catch (_: NoSuchFieldException) {
                            null
                        }
                    }
                },
            )
            val sortByUser = bindings.sortByUser
            val sortOnDefault = bindings.sortOnDefault
            val sortByDimension = bindings.sortByDimension
            val getSortedDimension = bindings.getSortedDimension
            val getSize = bindings.getSize
            val getDimensionAt = bindings.getDimensionAt
            val getId = bindings.getId
            val getLabel = bindings.getLabel
            val getDirection = bindings.getDirection
            val getCapability = bindings.getCapability
            val getVisibility = bindings.getVisibility
            val userSpecified = bindings.userSpecified
            reportOnce("sort/binding", "Sort: ${bindings.source} binding validated")

            fun labelOf(context: Context, item: Any): String? {
                val id = getLabel.invoke(item) as Int
                return try {
                    context.resources.getResourceEntryName(id)
                } catch (_: android.content.res.Resources.NotFoundException) {
                    null
                }
            }

            // Both callbacks stay passive until the pair is fully installed.
            val active = AtomicBoolean(false)
            val installed = mutableListOf<XposedInterface.HookHandle>()
            try {
                // Capture only an actual, successful user action, never a cursor refresh.
                installed += hook(sortByUser).setId("sort.save").intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        if (!active.get()) return result
                        runSafe("persistentSort/save") {
                            val instance = chain.thisObject ?: return@runSafe
                            val context = appContext ?: run {
                                reportOnce("sort/save-no-context", "Sort: save skipped; context unavailable")
                                return@runSafe
                            }
                            val requestedId = chain.args.getOrNull(0) as? Int ?: return@runSafe
                            val requestedDirection = chain.args.getOrNull(1) as? Int ?: return@runSafe
                            if (!SortPolicy.validDirection(requestedDirection)) return@runSafe
                            val current = getSortedDimension.invoke(instance) ?: return@runSafe
                            if (getId.invoke(current) != requestedId ||
                                getDirection.invoke(current) != requestedDirection ||
                                ((getCapability.invoke(current) as Int) and requestedDirection) == 0) {
                                return@runSafe
                            }
                            val label = labelOf(context, current) ?: return@runSafe
                            val prefs = context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE)
                            val stored = prefs.all
                            if (stored[PREF_SORT_LABEL] != label ||
                                stored[PREF_SORT_DIRECTION] != requestedDirection) {
                                prefs.edit()
                                    .putString(PREF_SORT_LABEL, label)
                                    .putInt(PREF_SORT_DIRECTION, requestedDirection)
                                    .remove("sort_position")
                                    .remove("sort_dimension_id")
                                    .apply()
                            }
                            reportOnce("sort/saved", "Sort: user preference saved")
                        }
                        return result
                    }
                })
                reportDeoptimization("sort.save", sortByUser)

                // Resolve the current resource label to its actual dimension and use
                // DocumentsUI's native sorting transition, including listener updates.
                installed += hook(sortOnDefault).setId("sort.restore").intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!active.get()) return chain.proceed()
                        var selected: Any? = null
                        var direction = 0
                        val instance = chain.thisObject ?: return chain.proceed()
                        runSafe("persistentSort/resolve") {
                            val context = appContext ?: run {
                                reportOnce("sort/restore-no-context", "Sort: restore skipped; context unavailable")
                                return@runSafe
                            }
                            if (userSpecified.getBoolean(instance)) return@runSafe
                            // Read both preference values from a single synchronized snapshot.
                            val stored = context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE).all
                            val label = stored[PREF_SORT_LABEL] as? String ?: return@runSafe
                            val savedDirection = stored[PREF_SORT_DIRECTION] as? Int ?: return@runSafe
                            if (!SortPolicy.validDirection(savedDirection)) return@runSafe
                            val count = getSize.invoke(instance) as Int
                            if (!SortPolicy.validCount(count)) {
                                reportOnce("sort/count", "Sort: unexpected dimension count; using stock default")
                                return@runSafe
                            }
                            val candidates = (0 until count).asSequence()
                                .mapNotNull { getDimensionAt.invoke(instance, it) }
                            val target = SortPolicy.select(
                                candidates, label, savedDirection,
                                labelOf = { labelOf(context, it) },
                                isVisible = { getVisibility.invoke(it) == android.view.View.VISIBLE },
                                capabilityOf = { getCapability.invoke(it) as Int },
                            )
                            if (target == null) {
                                reportOnce("sort/no-match", "Sort: saved dimension missing, ambiguous or unsupported; using stock default")
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
                            reportOnce("sort/restored", "Sort: saved preference restored")
                        } catch (error: InvocationTargetException) {
                            throw (error.cause ?: error)
                        } catch (_: IllegalAccessException) {
                            reportOnce("sort/access", "Sort: native transition inaccessible; using stock default")
                            return chain.proceed()
                        }
                        return null
                    }
                })
                reportDeoptimization("sort.restore", sortOnDefault)
                active.set(true)
            } catch (error: Throwable) {
                active.set(false)
                for (handle in installed.asReversed()) {
                    runSafe("sort/rollback") { handle.unhook() }
                }
                throw error
            }
            log(Log.INFO, "xDocUI", "Persistent sorting hooks installed (native model transitions)")
        }
    }

    private fun optionalClass(loader: ClassLoader, name: String): Class<*>? =
        try {
            loader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun reportDeoptimization(id: String, method: Method) {
        runSafe("deoptimize/$id") {
            if (!deoptimize(method)) {
                if (reportedEvents.add("deoptimize/$id")) {
                    log(Log.WARN, "xDocUI", "Sort: deoptimization not confirmed for $id")
                }
            }
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
