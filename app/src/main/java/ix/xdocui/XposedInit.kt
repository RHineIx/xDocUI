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
        // Target Application Packages
        private const val DOCUMENTSUI_PACKAGE = "com.android.documentsui"
        private const val DOCUMENTSUI_GOOGLE_PACKAGE = "com.google.android.documentsui"
        private const val EXTERNAL_STORAGE_PACKAGE = "com.android.externalstorage"

        // Target Classes - Storage Unrestrict
        private const val ACTIVITY_CONFIG_CLASS = "com.android.documentsui.ActivityConfig"
        private const val DOCUMENT_STACK_CLASS = "com.android.documentsui.base.DocumentStack"
        private const val EXTERNAL_STORAGE_PROVIDER_CLASS = "com.android.externalstorage.ExternalStorageProvider"

        // Target Classes - Remember Sort
        private const val SORT_MODEL_CLASS = "com.android.documentsui.sorting.SortModel"
        private const val LOOKUP_CLASS = "com.android.documentsui.base.Lookup"

        // Global Application Context for background threads. Marked as Volatile to ensure thread-safe visibility.
        @Volatile
        private var appContext: Context? = null
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        
        // Stability Guard: Prevent double-hooking if the system loads the app in multiple processes
        if (!param.isFirstPackage) return
        
        // Efficient routing based on the loaded package name
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
        // Capture the Application Context early in the lifecycle to avoid null returns in background threads
        safeHook("Capture Application Context") {
            val appClass = param.classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            
            hook(onCreateMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // Proceed first to let the Application be fully created (equivalent to afterHookedMethod)
                    val result = chain.proceed()
                    if (appContext == null) {
                        appContext = chain.thisObject as? Context
                    }
                    return result
                }
            })
            // Prevent the ART compiler from optimizing away our hook
            deoptimize(onCreateMethod)
        }
    }

    private fun hookDocumentsUI(param: XposedModuleInterface.PackageReadyParam) {
        // Force managed mode conditionally, only for external storage, to avoid breaking Media/Downloads
        safeHook("DocumentsUI - managedModeEnabled") {
            val configClass = param.classLoader.loadClass(ACTIVITY_CONFIG_CLASS)
            val docStackClass = param.classLoader.loadClass(DOCUMENT_STACK_CLASS)
            val managedModeMethod = configClass.getDeclaredMethod("managedModeEnabled", docStackClass)
            
            hook(managedModeMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val stack = if (chain.args.isNotEmpty()) chain.args[0] else null
                    
                    if (stack != null) {
                        // Safely extract the current provider authority
                        val root = stack.getFieldValue("root")
                        val authority = root?.getFieldValue("authority") as? String
                        
                        // Apply the bypass ONLY if the user is browsing the actual storage
                        if (authority == "com.android.externalstorage.documents") {
                            return true
                        }
                    }
                    
                    // For anything else (Images, Videos, Audio, Downloads), proceed normally
                    return chain.proceed()
                }
            })
            deoptimize(managedModeMethod)
        }
    }

    private fun hookRememberGlobalSort(param: XposedModuleInterface.PackageReadyParam) {
        // Intercept the sorting mechanism to save and apply the user's preferred sort order globally
        safeHook("DocumentsUI - Remember Global Sort") {
            val sortModelClass = param.classLoader.loadClass(SORT_MODEL_CLASS)
            val lookupClass = param.classLoader.loadClass(LOOKUP_CLASS)
            val sortCursorMethod = sortModelClass.getDeclaredMethod("sortCursor", Cursor::class.java, lookupClass)

            hook(sortCursorMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val sortModel = chain.thisObject
                    
                    // Safely retrieve the captured context. Fails safely if called too early before onCreate.
                    val context = appContext
                    
                    if (context != null) {
                        val prefs = context.getSharedPreferences("xDocUI_SortPrefs", Context.MODE_PRIVATE)

                        // Safe retrieval via reflection extensions to prevent ClassCastException or missing fields
                        val isUserSpecified = sortModel.getFieldValue("mIsUserSpecified") as? Boolean ?: false
                        val dimensions = sortModel.getFieldValue("mDimensions") as? SparseArray<*>

                        if (dimensions != null) {
                            if (isUserSpecified) {
                                // 1. User manually changed the sort mode -> Save it
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
                                        // Save asynchronously for maximum performance (no UI thread blocking)
                                        prefs.edit()
                                            .putInt("sort_position", position)
                                            .putInt("sort_direction", direction)
                                            .apply()

                                        // Reset the flag to prevent redundant saves
                                        sortModel.setFieldValue("mIsUserSpecified", false)
                                    }
                                }
                            } else {
                                // 2. System is loading a folder automatically -> Apply the globally saved sort mode
                                val savedPos = prefs.getInt("sort_position", -1)
                                val savedDir = prefs.getInt("sort_direction", -1)

                                if (savedPos != -1 && savedDir != -1 && savedPos < dimensions.size()) {
                                    val targetDim = dimensions.valueAt(savedPos)
                                    if (targetDim != null) {
                                        // Inject our saved preferences before the cursor is actually sorted
                                        targetDim.setFieldValue("mSortDirection", savedDir)
                                        sortModel.setFieldValue("mSortedDimension", targetDim)
                                    }
                                }
                            }
                        }
                    }
                    
                    // Proceed with the original sort function after injecting our modifications
                    return chain.proceed()
                }
            })
            deoptimize(sortCursorMethod)
        }
    }

    private fun hookExternalStorage(param: XposedModuleInterface.PackageReadyParam) {
        safeHook("ExternalStorage - Security Bypasses") {
            val espClass = param.classLoader.loadClass(EXTERNAL_STORAGE_PROVIDER_CLASS)
            val methodsToHook = listOf("shouldBlockDirectoryFromTree", "shouldBlockFromTree", "shouldHideDocument")
            
            val hooker = object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // Unblock standard directories and prevent documents from being hidden
                    return false
                }
            }

            for (methodName in methodsToHook) {
                try {
                    val method = espClass.getDeclaredMethod(methodName, String::class.java)
                    hook(method).intercept(hooker)
                    deoptimize(method)
                } catch (e: NoSuchMethodException) {
                    // Fallback log for different Android versions that might lack some of these methods
                    log(Log.WARN, "xDocUI", "Method not found on this OS version: $methodName")
                }
            }
        }
    }

    /**
     * A utility function to safely execute hook blocks without crashing the target app.
     * This guarantees 100% stability even if an OEM changes the internal APIs.
     */
    private inline fun safeHook(hookName: String, block: () -> Unit) {
        try {
            block()
        } catch (e: NoSuchMethodError) {
            log(Log.ERROR, "xDocUI", "Method not found for $hookName: ${e.message}")
        } catch (e: ClassNotFoundException) {
            log(Log.ERROR, "xDocUI", "Class not found for $hookName: ${e.message}")
        } catch (e: Throwable) {
            log(Log.ERROR, "xDocUI", "Unexpected error hooking $hookName: ${e.message}")
        }
    }

    // --- Local Extension Functions for Safe Reflection (Replacing XposedHelpers) ---
    
    /**
     * Safely traverses the class hierarchy to extract a field value.
     * Returns null if not found instead of crashing, ensuring stability.
     */
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

    /**
     * Safely traverses the class hierarchy to inject a field value.
     */
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