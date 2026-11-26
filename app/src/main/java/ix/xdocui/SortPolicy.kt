package ix.xdocui

/** Pure selection policy; independent of Android and Xposed. */
internal object SortPolicy {
    fun validDirection(direction: Int): Boolean = direction == 1 || direction == 2

    fun validCount(count: Int): Boolean = count in 1..128

    fun <T : Any> select(
        candidates: Sequence<T>,
        label: String,
        direction: Int,
        labelOf: (T) -> String?,
        isVisible: (T) -> Boolean,
        capabilityOf: (T) -> Int,
    ): T? {
        if (label.isBlank() || !validDirection(direction)) return null
        var match: T? = null
        for (candidate in candidates) {
            if (labelOf(candidate) != label) continue
            // Reject ambiguous labels even when one duplicate is hidden.
            if (match != null) return null
            match = candidate
        }
        val target = match ?: return null
        if (!isVisible(target) || (capabilityOf(target) and direction) == 0) return null
        return target
    }
}
