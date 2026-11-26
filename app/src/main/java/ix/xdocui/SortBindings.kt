package ix.xdocui

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Binds the sorting contract before installing any hook. The fallback only
 * accepts a single model type agreed on by at least two independent anchors.
 * Method names remain semantic anchors; no signature-only guesses.
 */
internal class SortBindings private constructor(
    val source: String,
    val sortByUser: Method,
    val sortOnDefault: Method,
    val sortByDimension: Method,
    val getSortedDimension: Method,
    val getSize: Method,
    val getDimensionAt: Method,
    val getId: Method,
    val getLabel: Method,
    val getDirection: Method,
    val getCapability: Method,
    val getVisibility: Method,
    val userSpecified: Field,
) {
    companion object {
        fun resolve(namedModel: Class<*>?, anchorModels: Sequence<Class<*>>): SortBindings {
            if (namedModel != null) {
                // A present but incompatible known class is not permission to guess.
                return bind(namedModel, "named")
            }
            val anchors = anchorModels.toList()
            require(anchors.size >= 2 && anchors.distinct().size == 1) {
                "Sort model anchors missing or conflicting"
            }
            return bind(anchors.first(), "structural")
        }

        private fun bind(model: Class<*>, source: String): SortBindings {
            val intType = Int::class.javaPrimitiveType!!
            val sorted = model.getDeclaredMethod("getSortedDimension")
            val dimension = sorted.returnType
            require(!dimension.isPrimitive && !dimension.isArray && dimension != Any::class.java &&
                dimension != model && dimension.classLoader == model.classLoader) {
                "Invalid sort dimension type"
            }
            val getSortedDimension = model.checked("getSortedDimension", dimension)
            val getDimensionAt = model.checked("getDimensionAt", dimension, intType)
            val userSpecified = model.getDeclaredField("mIsUserSpecified").apply {
                require(type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers)) {
                    "Invalid user-specified field"
                }
                isAccessible = true
            }
            return SortBindings(
                source = source,
                sortByUser = model.checked("sortByUser", Void.TYPE, intType, intType),
                sortOnDefault = model.checked("sortOnDefault", Void.TYPE),
                sortByDimension = model.checked("sortByDimension", Void.TYPE, dimension, intType),
                getSortedDimension = getSortedDimension,
                getSize = model.checked("getSize", intType),
                getDimensionAt = getDimensionAt,
                getId = dimension.checked("getId", intType),
                getLabel = dimension.checked("getLabelId", intType),
                getDirection = dimension.checked("getSortDirection", intType),
                getCapability = dimension.checked("getSortCapability", intType),
                getVisibility = dimension.checked("getVisibility", intType),
                userSpecified = userSpecified,
            )
        }

        private fun Class<*>.checked(name: String, result: Class<*>, vararg args: Class<*>): Method =
            getDeclaredMethod(name, *args).apply {
                require(returnType == result && !Modifier.isStatic(modifiers) &&
                    !Modifier.isAbstract(modifiers)) { "Unexpected signature for $name" }
                isAccessible = true
            }
    }
}
