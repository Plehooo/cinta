package com.plehooo.galericinta

import com.plehooo.galericinta.domain.SlotConfig
import com.plehooo.galericinta.domain.normalizeRotationInterval
import com.plehooo.galericinta.domain.nextRotationMediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotConfigTest {
    @Test fun emptySlotHasNoMedia() {
        val slot = SlotConfig(slotId = "slot1")
        assertEquals(null, slot.mediaId)
        assertTrue(slot.rotationIds.isEmpty())
    }

    @Test fun slideshowIntervalUsesSafeDefaultForUnsupportedValues() {
        assertEquals(60, normalizeRotationInterval(17))
        assertEquals(1440, normalizeRotationInterval(1440))
    }

    @Test fun slideshowRotationWrapsAndSkipsDuplicateIds() {
        assertEquals("b", nextRotationMediaId("a", listOf("a", "b", "b", "c")))
        assertEquals("a", nextRotationMediaId("c", listOf("a", "b", "c")))
        assertEquals("a", nextRotationMediaId("missing", listOf("a", "b")))
        assertEquals(null, nextRotationMediaId("a", listOf("a", "a")))
    }
}
