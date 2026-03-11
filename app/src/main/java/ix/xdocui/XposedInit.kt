package ix.xdocui

import android.content.Context
import android.database.Cursor
import android.util.SparseArray
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class XposedInit : IXposedHookLoadPackage {

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
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Efficient routing based on the loaded package name
        when (lpparam.packageName) {
            DOCUMENTSUI_PACKAGE, DOCUMENTSUI_GOOGLE_PACKAGE -> {
                hookDocumentsUI(lpparam)
                hookRememberGlobalSort(lpparam)
            }
            EXTERNAL_STORAGE_PACKAGE -> {
                hookExternalStorage(lpparam)
            }
        }
    }

    private fun hookDocumentsUI(lpparam: LoadPackageParam) {
        // Force managed mode to be true, allowing broader access in the UI
        safeHook("DocumentsUI - managedModeEnabled") {
            XposedHelpers.findAndHookMethod(
                ACTIVITY_CONFIG_CLASS,
                lpparam.classLoader,
                "managedModeEnabled",
                DOCUMENT_STACK_CLASS,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
        }
    }

    private fun hookRememberGlobalSort(lpparam: LoadPackageParam) {
        // Intercept the sorting mechanism to save and apply the user's preferred sort order globally
        safeHook("DocumentsUI - Remember Global Sort") {
            val lookupClass = XposedHelpers.findClass(LOOKUP_CLASS, lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                SORT_MODEL_CLASS,
                lpparam.classLoader,
                "sortCursor",
                Cursor::class.java,
                lookupClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sortModel = param.thisObject
                        
                        // Safely get the current application context via Reflection to avoid compile-time stub errors
                        val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                        val app = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? Context ?: return
                        
                        // Updated SharedPreferences name to match the new module identity
                        val prefs = app.getSharedPreferences("xDocUI_SortPrefs", Context.MODE_PRIVATE)

                        val isUserSpecified = XposedHelpers.getBooleanField(sortModel, "mIsUserSpecified")
                        val dimensions = XposedHelpers.getObjectField(sortModel, "mDimensions") as? SparseArray<*> ?: return

                        if (isUserSpecified) {
                            // 1. User manually changed the sort mode -> Save it
                            val currentDim = XposedHelpers.getObjectField(sortModel, "mSortedDimension") ?: return
                            val direction = XposedHelpers.getIntField(currentDim, "mSortDirection")

                            // Find the position (ID) of the selected dimension
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
                                XposedHelpers.setBooleanField(sortModel, "mIsUserSpecified", false)
                            }
                        } else {
                            // 2. System is loading a folder automatically -> Apply the globally saved sort mode
                            val savedPos = prefs.getInt("sort_position", -1)
                            val savedDir = prefs.getInt("sort_direction", -1)

                            if (savedPos != -1 && savedDir != -1 && savedPos < dimensions.size()) {
                                val targetDim = dimensions.valueAt(savedPos)
                                if (targetDim != null) {
                                    // Inject our saved preferences before the cursor is actually sorted
                                    XposedHelpers.setIntField(targetDim, "mSortDirection", savedDir)
                                    XposedHelpers.setObjectField(sortModel, "mSortedDimension", targetDim)
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun hookExternalStorage(lpparam: LoadPackageParam) {
        // Unblock standard directories from the tree
        safeHook("ExternalStorage - shouldBlockDirectoryFromTree") {
            XposedHelpers.findAndHookMethod(
                EXTERNAL_STORAGE_PROVIDER_CLASS,
                lpparam.classLoader,
                "shouldBlockDirectoryFromTree",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )
        }

        // Fallback hook for different Android versions
        safeHook("ExternalStorage - shouldBlockFromTree") {
            XposedHelpers.findAndHookMethod(
                EXTERNAL_STORAGE_PROVIDER_CLASS,
                lpparam.classLoader,
                "shouldBlockFromTree",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )
        }

        // Prevent documents from being hidden
        safeHook("ExternalStorage - shouldHideDocument") {
            XposedHelpers.findAndHookMethod(
                EXTERNAL_STORAGE_PROVIDER_CLASS,
                lpparam.classLoader,
                "shouldHideDocument",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )
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
            XposedBridge.log("[xDocUI] Method not found for $hookName: ${e.message}")
        } catch (e: de.robv.android.xposed.XposedHelpers.ClassNotFoundError) {
            XposedBridge.log("[xDocUI] Class not found for $hookName: ${e.message}")
        } catch (e: Throwable) {
            XposedBridge.log("[xDocUI] Unexpected error hooking $hookName: ${e.message}")
        }
    }
}
