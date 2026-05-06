package ix.xdocui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.SparseArray
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
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

        private const val MENU_ITEM_GO_TO_PATH_ID = 1001

        @Volatile
        private var appContext: Context? = null
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)

        if (!param.isFirstPackage) return

        when (param.packageName) {
            DOCUMENTSUI_PACKAGE, DOCUMENTSUI_GOOGLE_PACKAGE -> {
                captureAppContext(param)
                hookDocumentsUI(param)
                hookRememberGlobalSort(param)
                hookActivityUI(param)
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

    private fun hookActivityUI(param: XposedModuleInterface.PackageReadyParam) {
        runSafe("hookActivityUI") {
            val activityClass = param.classLoader.loadClass("android.app.Activity")

            val onCreateMethod = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            val onCreateOptionsMenuMethod = activityClass.getDeclaredMethod("onCreateOptionsMenu", Menu::class.java)
            val onOptionsItemSelectedMethod = activityClass.getDeclaredMethod("onOptionsItemSelected", MenuItem::class.java)

            // Intercept onCreate for Forceful Navigation Fallback
            hook(onCreateMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.javaClass.name.startsWith("com.android.documentsui")) {
                        val intent = activity.intent
                        if (intent != null && intent.getBooleanExtra("xdocui_force_recreate", false)) {
                            val bundle = chain.args.firstOrNull() as? Bundle
                            bundle?.clear()
                            intent.removeExtra("xdocui_force_recreate")
                        }
                    }
                    return chain.proceed()
                }
            })
            deoptimize(onCreateMethod)

            // Inject the menu item gracefully
            hook(onCreateOptionsMenuMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    val menu = chain.args.firstOrNull() as? Menu

                    if (activity != null && menu != null && activity.javaClass.name.startsWith("com.android.documentsui")) {
                        if (menu.findItem(MENU_ITEM_GO_TO_PATH_ID) == null) {
                            menu.add(0, MENU_ITEM_GO_TO_PATH_ID, 0, "Go to Path").apply {
                                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                            }
                        }
                    }
                    return result
                }
            })
            deoptimize(onCreateOptionsMenuMethod)

            // Handle the menu item click
            hook(onOptionsItemSelectedMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val activity = chain.thisObject as? Activity
                    val item = chain.args.firstOrNull() as? MenuItem

                    if (activity != null && item != null && activity.javaClass.name.startsWith("com.android.documentsui")) {
                        if (item.itemId == MENU_ITEM_GO_TO_PATH_ID) {
                            showPathDialog(activity)
                            return true // Event consumed
                        }
                    }
                    return chain.proceed()
                }
            })
            deoptimize(onOptionsItemSelectedMethod)
        }
    }

    private fun showPathDialog(activity: Activity) {
        activity.runOnUiThread {
            // Build a stable layout programmatically
            val editText = EditText(activity).apply {
                hint = "e.g. Download or Android/data"
                maxLines = 1
                isSingleLine = true
            }

            val container = FrameLayout(activity).apply {
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(60, 20, 60, 20)
                editText.layoutParams = params
                addView(editText)
            }

            // Fallback to standard system dialog to guarantee stability and prevent theme clashes
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Jump to Path")
                .setView(container)
                .setPositiveButton("Go") { _, _ ->
                    val path = editText.text.toString()
                    if (path.isNotEmpty()) {
                        navigateToPath(activity, path)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun navigateToPath(activity: Activity, path: String) {
        val uri = buildUriFromPath(path)
        if (uri == null) {
            Toast.makeText(activity, "Invalid path format.", Toast.LENGTH_SHORT).show()
            return
        }

        activity.runOnUiThread {
            try {
                // 1. Get ActionHandler instance
                var actions = activity.getFieldValue("mActions")
                if (actions == null) {
                    val injector = activity.getFieldValue("mInjector")
                    actions = injector?.getFieldValue("actions")
                }
                if (actions == null) {
                    actions = activity
                }

                // 2. Direct Reflection Call
                val success = invokeLaunchToDocument(actions, uri)

                // 3. Fallback Mechanism if reflection fails
                if (!success) {
                    try {
                        activity.intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                        activity.intent.putExtra("xdocui_force_recreate", true)
                        activity.recreate()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Navigation failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Error resolving path.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun invokeLaunchToDocument(actions: Any?, uri: Uri): Boolean {
        if (actions == null) return false
        var clazz: Class<*>? = actions.javaClass

        while (clazz != null) {
            try {
                // Target internal method
                val method = clazz.getDeclaredMethod("launchToDocument", Uri::class.java)
                method.isAccessible = true

                val result = method.invoke(actions, uri)
                return (result as? Boolean) ?: true
            } catch (e: NoSuchMethodException) {
                // Traverse up to superclass
                clazz = clazz.superclass
            } catch (e: Exception) {
                return false
            }
        }

        return false
    }

    private fun buildUriFromPath(path: String): Uri? {
        var cleanPath = path.trim().replace("\\", "/")

        while (cleanPath.startsWith("/")) cleanPath = cleanPath.substring(1)
        while (cleanPath.endsWith("/")) cleanPath = cleanPath.substring(0, cleanPath.length - 1)

        var docId = ""

        if (cleanPath.startsWith("storage/emulated/0/")) {
            docId = "primary:" + cleanPath.removePrefix("storage/emulated/0/")
        } else if (cleanPath == "storage/emulated/0") {
            docId = "primary:"
        } else if (cleanPath.startsWith("sdcard/")) {
            docId = "primary:" + cleanPath.removePrefix("sdcard/")
        } else if (cleanPath == "sdcard") {
            docId = "primary:"
        } else if (cleanPath.startsWith("storage/")) {
            val parts = cleanPath.split("/")
            if (parts.size >= 2) {
                val volumeId = parts[1] // e.g. 1234-5678
                val relative = parts.drop(2).joinToString("/")
                docId = if (relative.isEmpty()) "$volumeId:" else "$volumeId:$relative"
            }
        } else {
            docId = "primary:$cleanPath"
        }

        if (docId.isNotEmpty()) {
            docId = docId.removeSuffix("/")
            return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        }
        return null
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