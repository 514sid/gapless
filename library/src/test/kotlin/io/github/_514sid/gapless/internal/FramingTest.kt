package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessFit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FramingTest {

    @Test
    fun `contain fits inside the box`() {
        assertEquals(1440 to 1080, fittedSize(1920, 1080, 4f / 3, cover = false)) // pillarbox
        assertEquals(1920 to 800, fittedSize(1920, 1080, 2.4f, cover = false)) // letterbox
        assertEquals(1080 to 608, fittedSize(1080, 1920, 16f / 9, cover = false)) // portrait screen
    }

    @Test
    fun `cover fills the box and spills over one side`() {
        assertEquals(1920 to 1440, fittedSize(1920, 1080, 4f / 3, cover = true))
        assertEquals(2592 to 1080, fittedSize(1920, 1080, 2.4f, cover = true))
        assertEquals(3413 to 1920, fittedSize(1080, 1920, 16f / 9, cover = true))
    }

    @Test
    fun `display size squares non-square pixels`() {
        assertEquals(ContentSize(1024f, 576f), ContentSize.of(720 * (1024f / 720), 576))
        assertEquals(null, ContentSize.of(0, 1080))
        assertEquals(null, ContentSize.of(1920, null))
    }

    @Test
    fun `content of the box's own shape fills it either way`() {
        assertEquals(1920 to 1080, fittedSize(1920, 1080, 16f / 9, cover = false))
        assertEquals(1920 to 1080, fittedSize(1920, 1080, 16f / 9, cover = true))
    }

    @Test
    fun `original keeps the content's own size, larger or smaller than the box`() {
        assertEquals(3840 to 2160, contentBox(1920, 1080, ContentSize(3840f, 2160f), GaplessFit.Original))
        assertEquals(640 to 480, contentBox(1920, 1080, ContentSize(640f, 480f), GaplessFit.Original))
    }

    @Test
    fun `each fit lays out 4 by 3 content in a 16 by 9 box`() {
        val size = ContentSize(800f, 600f)
        assertEquals(1440 to 1080, contentBox(1920, 1080, size, GaplessFit.Contain))
        assertEquals(1920 to 1440, contentBox(1920, 1080, size, GaplessFit.Cover))
        assertEquals(800 to 600, contentBox(1920, 1080, size, GaplessFit.Original))
        assertNull(contentBox(1920, 1080, size, GaplessFit.Stretch))
    }

    @Test
    fun `content of unknown size fills the box whatever the fit`() {
        for (fit in GaplessFit.entries) assertNull(contentBox(1920, 1080, null, fit))
    }
}
