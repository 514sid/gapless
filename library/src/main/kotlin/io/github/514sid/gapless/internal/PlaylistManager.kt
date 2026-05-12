package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessAsset

internal object PlaylistManager {
    /**
     * Builds the ordered playlist from [source].
     *
     * When only one asset exists it is cloned via [GaplessAsset.clone] so
     * the gapless double-buffer always has two distinct slot IDs to work with.
     *
     * When [shuffle] is true the list is randomized, ensuring the asset that was playing
     * last ([lastId]) does not appear first in the new order.
     */
    fun prepare(
        source: List<GaplessAsset>,
        shuffle: Boolean,
        lastId: String?,
    ): List<GaplessAsset> {
        if (source.isEmpty()) return emptyList()

        if (source.size == 1) {
            val only = source.first()
            return listOf(only, only.clone())
        }

        if (!shuffle) return source

        val shuffled = source.shuffled()
        val lastNormalized = lastId?.removeSuffix(GaplessAsset.CLONE_SUFFIX)

        return if (lastNormalized != null && shuffled.first().normalizedId == lastNormalized) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    /**
     * Searches [playlist] circularly from [currentId] and returns the next entry whose
     * schedule is currently active.  Returns null when nothing qualifies.
     */
    fun findNextActive(playlist: List<GaplessAsset>, currentId: String?): GaplessAsset? {
        if (playlist.isEmpty()) return null
        val currentIndex = playlist.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        for (i in 1..playlist.size) {
            val candidate = playlist[(currentIndex + i) % playlist.size]
            if (candidate.isActiveNow()) return candidate
        }
        return null
    }
}
