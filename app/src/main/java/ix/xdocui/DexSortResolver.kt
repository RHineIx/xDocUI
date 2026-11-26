package ix.xdocui

import android.content.Context
import android.content.pm.ApplicationInfo
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * Three literal fingerprints verified in the supplied Android 17 Files DEX.
 * Require unique methods, same declaring class, and both callers invoking
 * the transition. Never guess methods by signature alone.
 */
internal object DexSortResolver {
    fun resolve(
        context: Context, info: ApplicationInfo, loader: ClassLoader,
        report: (String) -> Unit,
    ): SortBindings {
        val prefs = context.getSharedPreferences("xDocUI_DexBindings", Context.MODE_PRIVATE)
        val key = DexFingerprint.key(context, info)
        val snapshot = prefs.all
        val cached = snapshot["mapping"] as? String
        if (snapshot["key"] == key && cached != null) {
            try {
                val parts = cached.split('|')
                require(parts.size == 4)
                val model = loader.loadClass(parts[0])
                val names = TransitionNames(parts[1], parts[2], parts[3])
                val result = SortBindings.fromFingerprint(model, names, "dex-cache")
                report("DEX cache hit")
                return result
            } catch (_: ReflectiveOperationException) {
                // Re-scan rather than using an invalid cached contract.
            } catch (_: IllegalArgumentException) {
                // Re-scan rather than using an invalid cached contract.
            }
        }
        prefs.edit().remove("key").remove("mapping").apply()
        report("DEX cache miss")
        System.loadLibrary("dexkit")
        val paths = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).distinct()
        // Scan the base and code-bearing splits independently, and reject duplicates.
        val matches = mutableListOf<Pair<String, TransitionNames>>()
        for (path in paths) {
            if (!DexFingerprint.hasDex(path)) continue
            DexKitBridge.create(path).use { bridge ->
                val saves = bridge.findMethod {
                    matcher {
                        returnType = "void"
                        paramTypes("int", "int")
                        usingStrings("Unknown column id: ")
                    }
                }
                val restores = bridge.findMethod {
                    matcher {
                        returnType = "void"
                        paramCount = 0
                        usingStrings("No default sort dimension.")
                    }
                }
                val transitions = bridge.findMethod {
                    matcher {
                        returnType = "void"
                        paramTypes(null, "int")
                        usingStrings("Unknown sort direction: ", " can't be sorted in direction:")
                    }
                }
                // A duplicated fingerprint anywhere in a code-bearing split is ambiguous.
                require(saves.size <= 1 && restores.size <= 1 && transitions.size <= 1) {
                    "Ambiguous DEX fingerprints"
                }
                val save = saves.singleOrNull() ?: return@use
                val restore = restores.singleOrNull() ?: return@use
                val transition = transitions.singleOrNull() ?: return@use
                val methods = listOf(save, restore, transition)
                if (methods.map { it.className }.distinct().size != 1 ||
                    methods.any { Modifier.isStatic(it.modifiers) || Modifier.isAbstract(it.modifiers) } ||
                    !calls(save, transition) || !calls(restore, transition)) return@use
                matches += save.className to TransitionNames(
                    save.methodName, restore.methodName, transition.methodName,
                )
            }
        }
        val match = matches.singleOrNull() ?: error("DEX transition fingerprints missing or ambiguous")
        val (className, names) = match
        val result = SortBindings.fromFingerprint(loader.loadClass(className), names, "dex-fingerprint")
        prefs.edit().putString("key", key)
            .putString("mapping", listOf(className, names.save, names.restore, names.transition).joinToString("|"))
            .apply()
        report("DEX fingerprints validated")
        return result
    }

    private fun calls(caller: MethodData, callee: MethodData): Boolean =
        caller.invokes.any { it.descriptor == callee.descriptor }
}
