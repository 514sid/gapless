package io.github._514sid.gapless

/** How a video or image whose shape differs from the player's is fitted to it. */
public enum class GaplessFit {
    /** All of it is shown, as large as fits, with bars of [GaplessAsset.backgroundColor] around it. */
    Contain,

    /** It covers the whole player, keeping its shape; whatever sticks out at the edges is cut off. */
    Cover,

    /** It is stretched to the player's shape, distorting it. */
    Stretch,

    /**
     * It is shown at its own size in pixels, centred and not scaled: cut off at the edges when
     * larger than the player, with bars of [GaplessAsset.backgroundColor] around it when smaller.
     * Pixels are the player's layout pixels, which on a TV whose UI runs at 1080p but whose panel is
     * 4K are half the panel's. An image is decoded whole, so keep images no larger than needed.
     */
    Original,
}
