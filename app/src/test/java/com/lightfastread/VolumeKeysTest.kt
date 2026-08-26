package com.lightfastread

import com.lightfastread.hw.volumePageStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which key turns a page which way.
 *
 * The keycodes are written as the numbers Android assigns them - 24 is `KEYCODE_VOLUME_UP`, 25 is
 * `KEYCODE_VOLUME_DOWN` - because that is the only thing this test can independently know. Flipping
 * the two in the source is precisely the mistake worth catching, and a test that reads the constants
 * back out of the same file would agree with any answer.
 */
class VolumeKeysTest {

    @Test
    fun `volume up turns forward, matching the wheel`() {
        assertEquals(1, volumePageStep(24))
    }

    @Test
    fun `volume down turns back`() {
        assertEquals(-1, volumePageStep(25))
    }

    @Test
    fun `every other key is left to whoever wanted it`() {
        // Nothing else may be swallowed by a reader: 4 is BACK, 66 is ENTER, 164 is VOLUME_MUTE,
        // and 0 is the unknown keycode a synthetic event arrives with.
        listOf(0, 4, 66, 164, 26, 27).forEach { assertNull(volumePageStep(it)) }
    }
}
