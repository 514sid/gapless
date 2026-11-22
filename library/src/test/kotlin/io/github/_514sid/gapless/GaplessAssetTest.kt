package io.github._514sid.gapless

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GaplessAssetTest {

    private fun asset(mimeType: String) = GaplessAsset(id = "a", uri = "file:///a", mimeType = mimeType)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "video/mp4",
            "Video/MP4",
            "application/x-mpegURL",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
            "application/vnd.ms-sstr+xml",
            "application/x-rtsp",
        ]
    )
    fun `video types`(mimeType: String) {
        val a = asset(mimeType)
        assertTrue(a.isVideo)
        assertFalse(a.isImage)
        assertFalse(a.isWeb)
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/jpeg", "IMAGE/PNG", "image/webp"])
    fun `image types`(mimeType: String) {
        assertTrue(asset(mimeType).isImage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/html", "text/html; charset=utf-8", "TEXT/HTML", "application/xhtml+xml"])
    fun `web types`(mimeType: String) {
        assertTrue(asset(mimeType).isWeb)
    }

    @ParameterizedTest
    @ValueSource(strings = ["audio/mpeg", "application/pdf", "text/plain", ""])
    fun `other types match no renderer`(mimeType: String) {
        val a = asset(mimeType)
        assertFalse(a.isVideo || a.isImage || a.isWeb)
    }
}
