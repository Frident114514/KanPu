package com.kanpu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    @Test fun doublePagesKeepFocusAcrossRotation() {
        assertEquals(VisiblePages(2, 3), PageLayout.visible(3, 5, true))
        assertEquals(VisiblePages(3, null), PageLayout.visible(3, 5, false))
        assertEquals(VisiblePages(4, null), PageLayout.visible(4, 5, true))
    }

    @Test fun doubleTurnNeverCreatesEmptyPage() {
        assertEquals(2, PageLayout.turn(0, 5, true, 1))
        assertEquals(4, PageLayout.turn(2, 5, true, 1))
        assertEquals(4, PageLayout.turn(4, 5, true, 1))
        assertEquals(0, PageLayout.turn(0, 5, true, -1))
    }
}
