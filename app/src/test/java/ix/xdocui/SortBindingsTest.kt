package ix.xdocui

import org.junit.Assert.*
import org.junit.Test

class SortBindingsTest {
    private class RenamedDimension {
        fun getId(): Int = 1
        fun getLabelId(): Int = 2
        fun getSortDirection(): Int = 1
        fun getSortCapability(): Int = 3
        fun getVisibility(): Int = 0
    }

    @Suppress("UNUSED_PARAMETER")
    private class RenamedModel {
        private var mIsUserSpecified = false
        fun sortByUser(id: Int, direction: Int) {}
        fun sortOnDefault() {}
        fun sortByDimension(dimension: RenamedDimension, direction: Int) {}
        fun getSortedDimension(): RenamedDimension = RenamedDimension()
        fun getDimensionAt(index: Int): RenamedDimension = RenamedDimension()
        fun getSize(): Int = 1
    }

    private class WrongModel

    @Test fun derivesDimensionTypeFromModel() {
        val resolved = SortBindings.resolve(RenamedModel::class.java, emptySequence())
        assertEquals("named", resolved.source)
        assertEquals(RenamedDimension::class.java, resolved.getSortedDimension.returnType)
        assertEquals(RenamedDimension::class.java, resolved.sortByDimension.parameterTypes.first())
    }

    @Test fun resolvesRenamedModelOnlyWhenAnchorsAgree() {
        val model = RenamedModel::class.java
        val resolved = SortBindings.resolve(null, sequenceOf(model, model))
        assertEquals("structural", resolved.source)
        assertEquals(model, resolved.sortByUser.declaringClass)
    }

    @Test fun rejectsInsufficientOrConflictingAnchors() {
        val model = RenamedModel::class.java
        assertThrows(IllegalArgumentException::class.java) {
            SortBindings.resolve(null, sequenceOf(model))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SortBindings.resolve(null, sequenceOf(model, model, WrongModel::class.java))
        }
    }

    @Test fun rejectsIncompatibleKnownModelWithoutGuessing() {
        val model = RenamedModel::class.java
        assertThrows(NoSuchMethodException::class.java) {
            SortBindings.resolve(WrongModel::class.java, sequenceOf(model, model))
        }
    }
}
