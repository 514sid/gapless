package io.github._514sid.gapless

import io.github._514sid.gapless.GaplessAsset.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GaplessAssetTest {

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
        assertEquals(Kind.Video, GaplessAsset.kindOf(mimeType))
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/jpeg", "IMAGE/PNG", "image/webp"])
    fun `image types`(mimeType: String) {
        assertEquals(Kind.Image, GaplessAsset.kindOf(mimeType))
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/html", "text/html; charset=utf-8", "TEXT/HTML", "application/xhtml+xml"])
    fun `web types`(mimeType: String) {
        assertEquals(Kind.Web, GaplessAsset.kindOf(mimeType))
    }

    @ParameterizedTest
    @ValueSource(strings = ["audio/mpeg", "application/pdf", "text/plain", ""])
    fun `other types match no asset type`(mimeType: String) {
        assertNull(GaplessAsset.kindOf(mimeType))
    }
}
