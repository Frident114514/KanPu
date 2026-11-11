package com.kanpu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FingeringSizeTest {
    @Test fun oldAnnotationsUseNewPresetsWithoutChangingSavedData() {
        assertEquals(FingeringSize.SMALL, FingeringSize.displayRatio(.026f))
        assertEquals(FingeringSize.MEDIUM, FingeringSize.displayRatio(.035f))
        assertEquals(FingeringSize.LARGE, FingeringSize.displayRatio(.048f))
    }

    @Test fun newPresetsKeepTheirSize() {
        listOf(FingeringSize.SMALL, FingeringSize.MEDIUM, FingeringSize.LARGE).forEach {
            assertEquals(it, FingeringSize.displayRatio(it))
        }
    }
}
