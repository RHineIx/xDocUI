package ix.xdocui

import org.junit.Assert.*
import org.junit.Test

class TransitionNamesTest {
    private class Dim {
        fun getId() = 1
        fun getLabelId() = 2
        fun getSortDirection() = 1
        fun getSortCapability() = 3
        fun getVisibility() = 0
    }

    @Suppress("UNUSED_PARAMETER")
    private class Model {
        private var mIsUserSpecified = false
        fun a(id: Int, direction: Int) {}
        fun b() {}
        fun c(dimension: Dim, direction: Int) {}
        fun getSortedDimension() = Dim()
        fun getDimensionAt(index: Int) = Dim()
        fun getSize() = 1
    }

    @Test fun bindsRenamedTransitions() {
        val binding = SortBindings.fromFingerprint(Model::class.java, TransitionNames("a", "b", "c"), "test")
        assertEquals("a", binding.sortByUser.name)
        assertEquals("b", binding.sortOnDefault.name)
        assertEquals("c", binding.sortByDimension.name)
    }

    @Test fun rejectsWrongSignatures() {
        assertThrows(NoSuchMethodException::class.java) {
            SortBindings.fromFingerprint(Model::class.java, TransitionNames("b", "a", "c"), "test")
        }
    }

    @Test fun rejectsMalformedAndDuplicateNames() {
        assertFalse(TransitionNames("a", "a", "c").isValid())
        assertFalse(TransitionNames("a", "b", "c|").isValid())
        assertFalse(TransitionNames("", "b", "c").isValid())
    }
}
