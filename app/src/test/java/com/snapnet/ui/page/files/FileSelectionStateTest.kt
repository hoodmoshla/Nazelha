package com.snapnet.ui.page.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSelectionStateTest {
    @Test
    fun togglesSingleKeyWithoutChangingOtherSelections() {
        assertEquals(setOf("a", "b", "c"), toggleFileSelection(setOf("a", "b"), "c"))
        assertEquals(setOf("a"), toggleFileSelection(setOf("a", "b"), "b"))
    }

    @Test
    fun selectsAndClearsVisibleFilesWithoutDroppingHiddenSelections() {
        val initial = setOf("hidden", "visible-a")
        val visible = setOf("visible-a", "visible-b")
        assertEquals(
            setOf("hidden", "visible-a", "visible-b"),
            selectVisibleFiles(initial, visible, selected = true),
        )
        assertEquals(
            setOf("hidden"),
            selectVisibleFiles(initial, visible, selected = false),
        )
    }
}
