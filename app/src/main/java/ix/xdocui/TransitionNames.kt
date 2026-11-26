package ix.xdocui

internal data class TransitionNames(val save: String, val restore: String, val transition: String) {
    fun isValid(): Boolean = listOf(save, restore, transition).all {
        it.isNotBlank() && it.length <= 256 && !it.contains('|') && !it.startsWith('<')
    } // Obfuscators may reuse the same name for different signatures.
}
