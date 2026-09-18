package vn.chamcong.iot.ui.shifts

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.chamcong.iot.model.ShiftCategory

class ShiftPresentationTest {
    @Test
    fun changingDefaultMorningCategoryToEveningUpdatesTheDefaultName() {
        assertEquals(
            "Ca chiều",
            shiftNameForCategoryChange("Ca sáng", ShiftCategory.EVENING.name)
        )
    }

    @Test
    fun changingCategoryKeepsAnAdminProvidedCustomName() {
        assertEquals(
            "Ca kho vận",
            shiftNameForCategoryChange("Ca kho vận", ShiftCategory.EVENING.name)
        )
    }
}
