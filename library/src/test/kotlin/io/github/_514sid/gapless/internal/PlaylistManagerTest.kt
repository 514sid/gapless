package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayerConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlaylistManagerTest {
    private lateinit var manager: PlaylistManager

    private fun createTestAsset(
        id: String,
        duration: Long = 1000L,
        active: Boolean = true
    ): GaplessAsset {
        return GaplessAsset(
            id = id,
            uri = "test://uri",
            mimeType = "video/mp4",
            durationMs = duration,
            endDate = if (active) null else 1L
        )
    }

    @BeforeEach
    fun setup() {
        manager = PlaylistManager()
    }

    @Test
    fun `advance loops back to current asset if all other assets are inactive`() {
        val asset1 = createTestAsset("asset_1", duration = 100L, active = true)
        val asset2 = createTestAsset("asset_2", duration = 100L, active = false)

        val config = GaplessPlayerConfig(preloadThresholdMs = 200L)

        manager.update(listOf(asset1, asset2), shuffle = false, config = config)

        val firstGen = manager.currentAsset.value?.generation ?: 0L

        manager.advance()

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)
        assertTrue((manager.currentAsset.value?.generation ?: 0L) > firstGen)

        assertEquals("asset_1", manager.preloadAsset.value?.id)
    }

    @Test
    fun `update with single asset initializes original as current and no preload before tick`() {
        val asset1 = createTestAsset("asset_1", duration = 100L, active = true)

        manager.update(
            listOf(asset1),
            shuffle = false,
            config = GaplessPlayerConfig(preloadThresholdMs = 200L)
        )

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)

        assertNull(manager.preloadAsset.value)
    }

    @Test
    fun `advance multiple times with single asset and shuffle enabled continuously loops`() {
        val asset1 = createTestAsset("asset_1", duration = 1000L, active = true)
        val config = GaplessPlayerConfig(preloadThresholdMs = 2000L)

        manager.update(listOf(asset1), shuffle = true, config = config)

        val gen1 = manager.currentAsset.value?.generation ?: 0L
        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)

        manager.advance()
        val gen2 = manager.currentAsset.value?.generation ?: 0L

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)
        assertTrue(gen2 > gen1)

        assertEquals("asset_1", manager.preloadAsset.value?.id)
    }

    @Test
    fun `tick multiple times with single asset continuously increments generation`() {
        val asset1 = createTestAsset("asset_1", duration = 100L, active = true)
        val config = GaplessPlayerConfig(
            tickIntervalMs = 100L,
            preloadThresholdMs = 200L
        )

        manager.update(listOf(asset1), shuffle = false, config = config)

        val gen1 = manager.currentAsset.value?.generation ?: 0L

        manager.tick()
        val gen2 = manager.currentAsset.value?.generation ?: 0L

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)
        assertTrue(gen2 > gen1)

        assertEquals("asset_1", manager.preloadAsset.value?.id)

        manager.tick()
        val gen3 = manager.currentAsset.value?.generation ?: 0L

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)
        assertTrue(gen3 > gen2)
    }

    @Test
    fun `tick sets preload correctly with one active asset`() {
        val asset1 = createTestAsset("asset_1", duration = 100L, active = true)
        val asset2 = createTestAsset("asset_2", duration = 100L, active = false)

        val config = GaplessPlayerConfig(
            tickIntervalMs = 100L,
            preloadThresholdMs = 200L
        )

        manager.update(listOf(asset1, asset2), shuffle = false, config = config)

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)
        assertNull(manager.preloadAsset.value)

        val gen1 = manager.currentAsset.value?.generation ?: 0L

        manager.tick()

        assertEquals("asset_1", manager.currentAsset.value?.asset?.id)
        assertEquals("asset_1", manager.preloadAsset.value?.id)
        assertTrue((manager.currentAsset.value?.generation ?: 0L) > gen1)
    }

    @Test
    fun `advance resets state when NO active assets exist`() {
        val asset1 = createTestAsset("asset_1", duration = 100L, active = false)

        manager.update(
            listOf(asset1),
            shuffle = false,
            config = GaplessPlayerConfig()
        )

        assertNull(manager.currentAsset.value)
        assertNull(manager.preloadAsset.value)
    }

    @Test
    fun `playlist update preserves generation when current asset remains valid`() {
        val asset1 = createTestAsset("asset_1")
        val config = GaplessPlayerConfig()

        manager.update(listOf(asset1), false, config)
        val firstState = manager.currentAsset.value
        val firstGen = firstState?.generation ?: 0L

        manager.update(
            listOf(asset1, createTestAsset("asset_2")),
            false,
            config
        )

        val secondState = manager.currentAsset.value

        assertEquals(firstGen, secondState?.generation)
        assertEquals("asset_1", secondState?.asset?.id)

        assertNull(manager.preloadAsset.value)
    }

    @Test
    fun `update does not schedule preload before tick`() {
        val asset1 = createTestAsset("asset_1", duration = 1000L, active = true)

        manager.update(
            listOf(asset1),
            shuffle = false,
            config = GaplessPlayerConfig(preloadThresholdMs = 2000L)
        )

        assertNull(manager.preloadAsset.value)
    }
}