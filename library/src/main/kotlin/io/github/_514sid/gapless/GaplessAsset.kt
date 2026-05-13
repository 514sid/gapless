package io.github._514sid.gapless

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Represents a single piece of media in the gapless playlist.
 *
 * This data class holds all metadata required to render and schedule an asset,
 * including its URI, type, and temporal constraints (date, days of week, and time of day).
 *
 * @property id Unique identifier for this asset. Must be stable across list updates.
 * @property uri Local file path, `content://` URI, or remote URL to the media.
 * @property mimeType MIME type (e.g., "video/mp4", "image/jpeg"). Determines the rendering engine.
 * @property durationMs Duration to display this asset, in milliseconds. Defaults to 10 seconds.
 * @property startDate Optional epoch-millisecond timestamp. The asset will not play before this time.
 * @property endDate Optional epoch-millisecond timestamp. The asset will not play after this time.
 * @property playDays Optional set of [DayOfWeek] indicating which days the asset is allowed to play.
 * Null or empty means it is allowed every day.
 * @property playTimeFrom Optional start of the daily time window in "HH:mm" or "HH:mm:ss" format.
 * @property playTimeTo Optional end of the daily time window in "HH:mm" or "HH:mm:ss" format.
 * Supports midnight-crossing ranges (e.g., 22:00 to 06:00).
 * @property refreshIntervalMs Reload interval in milliseconds for web assets. Null means no auto-refresh.
 */
data class GaplessAsset(
    val id: String,
    val uri: String,
    val mimeType: String,
    val durationMs: Long = 10_000L,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val playDays: Set<DayOfWeek>? = null,
    val playTimeFrom: String? = null,
    val playTimeTo: String? = null,
    val refreshIntervalMs: Long? = null,
) {
    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm[:ss]")
        private const val MIME_PREFIX_VIDEO = "video/"
        private const val MIME_PREFIX_IMAGE = "image/"
        private val HLS_DASH_RTSP_MIMES = setOf(
            "application/x-mpegURL",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
            "application/vnd.ms-sstr+xml",
            "application/x-rtsp"
        )
    }

    /**
     * Determines if the asset is a video stream or file based on its [mimeType].
     */
    val isVideo: Boolean
        get() = mimeType.startsWith(MIME_PREFIX_VIDEO) || mimeType in HLS_DASH_RTSP_MIMES

    /**
     * Determines if the asset is an image based on its [mimeType].
     */
    val isImage: Boolean
        get() = mimeType.startsWith(MIME_PREFIX_IMAGE)

    /**
     * Determines if the asset should be rendered as a web page (fallback if not video or image).
     */
    val isWeb: Boolean
        get() = !isVideo && !isImage

    /**
     * Checks if the asset is allowed to play right now, evaluated against the system clock.
     */
    fun isActiveNow(): Boolean = isActiveAt(Clock.systemDefaultZone())

    /**
     * Checks if the asset is allowed to play at the given time context.
     * Evaluates the start/end dates, the allowed days of the week, and the time-of-day window.
     *
     * @param clock The [Clock] to evaluate the current time against. Inject this for unit testing.
     * @return `true` if all temporal constraints are satisfied, `false` otherwise.
     */
    fun isActiveAt(clock: Clock): Boolean {
        val nowMs = clock.millis()

        if (startDate != null && nowMs < startDate) return false
        if (endDate != null && nowMs > endDate) return false

        if (!isDayValid(clock)) return false
        if (!isTimeWindowValid(clock)) return false

        return true
    }

    private fun isDayValid(clock: Clock): Boolean {
        if (playDays.isNullOrEmpty()) return true
        val todayDow = LocalDate.now(clock).dayOfWeek
        return todayDow in playDays
    }

    private fun isTimeWindowValid(clock: Clock): Boolean {
        if (playTimeFrom.isNullOrBlank() && playTimeTo.isNullOrBlank()) return true

        return try {
            val from = if (playTimeFrom.isNullOrBlank()) LocalTime.MIN else LocalTime.parse(playTimeFrom, TIME_FORMATTER)
            val to = if (playTimeTo.isNullOrBlank()) LocalTime.MAX else LocalTime.parse(playTimeTo, TIME_FORMATTER)
            val now = LocalTime.now(clock)

            if (to.isBefore(from)) {
                // Midnight-crossing window (e.g., 22:00 to 06:00)
                !now.isBefore(from) || now.isBefore(to)
            } else {
                // Standard window (e.g., 09:00 to 17:00)
                !now.isBefore(from) && now.isBefore(to)
            }
        } catch (_: DateTimeParseException) {
            // Failsafe: if the backend sends garbage data, allow it to play rather than crashing
            true
        }
    }
}