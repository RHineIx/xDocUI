package ix.xdocui

import org.junit.Assert.*
import org.junit.Test

class SortPolicyTest {
    private data class Dimension(
        val label: String?,
        val id: Int = 0,
        val visible: Boolean = true,
        val capability: Int = 3,
    )

    private fun select(vararg dimensions: Dimension, label: String = "sort_name", direction: Int = 1) =
        SortPolicy.select(dimensions.asSequence(), label, direction,
            Dimension::label, Dimension::visible, Dimension::capability)

    @Test fun selectsSemanticLabelAfterIdAndPositionChange() {
        val target = Dimension("sort_name", id = 999)
        assertSame(target, select(Dimension("sort_size", id = 1), target))
    }

    @Test fun rejectsDuplicateLabels() {
        assertNull(select(Dimension("sort_name"), Dimension("sort_name", visible = false)))
    }

    @Test fun rejectsHiddenOrMissingDimensions() {
        assertNull(select(Dimension("sort_name", visible = false)))
        assertNull(select(Dimension("sort_size"), Dimension(null)))
        assertNull(select())
    }

    @Test fun validatesDirectionAndCapability() {
        assertNull(select(Dimension("sort_name", capability = 1), direction = 2))
        assertNull(select(Dimension("sort_name"), direction = 0))
        assertNull(select(Dimension("sort_name"), direction = 3))
        assertNotNull(select(Dimension("sort_name", capability = 2), direction = 2))
        assertNotNull(select(Dimension("sort_name"), direction = 1))
    }

    @Test fun rejectsBlankLabelsAndInvalidCounts() {
        assertNull(select(Dimension(""), label = ""))
        assertFalse(SortPolicy.validCount(0))
        assertFalse(SortPolicy.validCount(129))
        assertTrue(SortPolicy.validCount(1))
        assertTrue(SortPolicy.validCount(128))
    }
}
