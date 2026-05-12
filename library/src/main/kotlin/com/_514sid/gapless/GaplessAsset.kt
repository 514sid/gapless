package com._514sid.gapless

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm[:ss]")

/**
 * Represents a single piece of media in the gapless playlist.
 *
 * @param id          Unique identifier for this asset. Must be stable across list updates.
 * @param uri         Local file path, content:// URI, or remote URL to the media.
 * @param mimeType    MIME type (e.g. "video/mp4", "image/jpeg"). Determines how the asset is rendered.
 * @param durationMs  How long this asset should display, in milliseconds. Defaults to 10 seconds.
 * @param startDate   Optional epoch-ms timestamp before which the asset should not play.
 * @param endDate     Optional epoch-ms timestamp after which the asset should not play.
 * @param playDays    Optional set of weekday indices (0 = Monday … 6 = Sunday) on which the asset
 *                    is allowed to play. Null or empty means every day.
 * @param playTimeFrom Optional start of the daily time window in "HH:mm" or "HH:mm:ss" format.
 * @param playTimeTo   Optional end of the daily time window. Supports midnight-crossing ranges.
 * @param refreshIntervalMs For web assets: reload interval in milliseconds. Null means no auto-refresh.
 */
data class GaplessAsset(
    val id: String,
    val uri: String,
    val mimeType: String,
    val durationMs: Long = 10_000L,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val playDays: Set<Int>? = null,
    val playTimeFrom: String? = null,
    val playTimeTo: String? = null,
    val refreshIntervalMs: Long? = null,
) {
    companion object {
        internal const val CLONE_SUFFIX = "_2"
    }

    val isVideo: Boolean
        get() = mimeType.startsWith("video/") ||
                mimeType == "application/x-mpegURL" ||
                mimeType == "application/vnd.apple.mpegurl" ||
                mimeType == "application/dash+xml" ||
                mimeType == "application/vnd.ms-sstr+xml" ||
                mimeType == "application/x-rtsp"

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isWeb: Boolean
        get() = !isVideo && !isImage

    val normalizedId: String
        get() = id.removeSuffix(CLONE_SUFFIX)

    fun clone(): GaplessAsset = copy(id = "$id$CLONE_SUFFIX")

    fun isActiveNow(): Boolean {
        val nowMs = System.currentTimeMillis()
        if (startDate != null && nowMs < startDate) return false
        if (endDate != null && nowMs > endDate) return false
        if (!isTodayValid()) return false
        if (!isTimeWindowValid()) return false
        return true
    }

    private fun isTodayValid(): Boolean {
        val days = playDays?.takeIf { it.isNotEmpty() } ?: return true
        val todayDow = LocalDate.now().dayOfWeek.value - 1 // 0=Mon..6=Sun
        return todayDow in days
    }

    private fun isTimeWindowValid(): Boolean {
        if (playTimeFrom.isNullOrBlank() || playTimeTo.isNullOrBlank()) return true
        return try {
            val from = LocalTime.parse(playTimeFrom, TIME_FORMATTER)
            val to = LocalTime.parse(playTimeTo, TIME_FORMATTER)
            val now = LocalTime.now()
            if (to.isBefore(from)) !now.isBefore(from) || now.isBefore(to)
            else !now.isBefore(from) && now.isBefore(to)
        } catch (_: Exception) {
            true
        }
    }
}
