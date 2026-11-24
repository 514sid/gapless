package io.github.gapless.sample

import android.content.Context
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessFit
import org.json.JSONObject

/**
 * The generated test media in assets/samples, described by samples.json. See samples/README.md
 * for what each asset and playlist is for.
 */
class SampleManifest(
    private val assets: Map<String, GaplessAsset>,
    private val broken: Set<String>,
    val playlists: Map<String, List<String>>,
) {

    /**
     * The assets of [name] in play order, or null if there is no such playlist. Without [allowUhd],
     * working videos larger than Full HD are left out; images and broken assets stay in.
     */
    fun playlist(name: String, volume: Float, allowUhd: Boolean): List<GaplessAsset>? =
        playlists[name]?.let { assets(it, volume, allowUhd) }

    /** The named assets in order, or null if any id is unknown. */
    fun assets(ids: List<String>, volume: Float, allowUhd: Boolean): List<GaplessAsset>? =
        ids.takeIf { it.all(assets::containsKey) }
            ?.map { id -> assets.getValue(id) }
            ?.filter { allowUhd || it.id in broken || !it.isUhdVideo() }
            ?.map { it.copy(volume = volume) }

    /** The same media with every page script and readiness wait removed, for comparison. */
    fun withoutScripts(): SampleManifest = SampleManifest(
        assets.mapValues { (_, a) -> a.copy(javaScript = null, waitForPageReady = false) },
        broken,
        playlists,
    )

    /** The same media with every video and image given [fit] and [backgroundColor], where set. */
    fun framed(fit: GaplessFit?, backgroundColor: Int?): SampleManifest = SampleManifest(
        assets.mapValues { (_, a) ->
            a.copy(fit = fit ?: a.fit, backgroundColor = backgroundColor ?: a.backgroundColor)
        },
        broken,
        playlists,
    )

    private fun GaplessAsset.isUhdVideo(): Boolean {
        if (!mimeType.startsWith("video/")) return false
        val w = width ?: return false
        val h = height ?: return false
        return maxOf(w, h) > 1920 || minOf(w, h) > 1080
    }

    companion object {
        const val PATH = "samples/samples.json"

        /** `contain`, `cover`, `stretch` or `original`, in any case. */
        fun parseFit(name: String): GaplessFit =
            GaplessFit.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown fit \"$name\"; use contain, cover, stretch or original")

        /**
         * `RRGGBB` or `AARRGGBB`, with or without a leading `#`. The device shell reads `#` as the
         * start of a comment, so launch extras leave it out.
         */
        fun parseColor(hex: String): Int =
            android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")

        fun load(context: Context): SampleManifest {
            val json = JSONObject(context.assets.open(PATH).bufferedReader().use { it.readText() })
            val list = json.getJSONArray("assets")
            val broken = mutableSetOf<String>()
            val assets = (0 until list.length()).associate { i ->
                val a = list.getJSONObject(i)
                val id = a.getString("id")
                if (a.optBoolean("broken")) broken += id
                id to GaplessAsset(
                    id = id,
                    uri = a.getString("uri"),
                    mimeType = a.getString("mimeType"),
                    width = a.optInt("width").takeIf { it > 0 },
                    height = a.optInt("height").takeIf { it > 0 },
                    durationMs = a.getLong("durationMs"),
                    javaScript = a.optString("javaScript").takeIf { it.isNotEmpty() },
                    waitForPageReady = a.optBoolean("waitForPageReady"),
                    fit = a.optString("fit").takeIf { it.isNotEmpty() }?.let(::parseFit) ?: GaplessFit.Contain,
                    backgroundColor = a.optString("background").takeIf { it.isNotEmpty() }
                        ?.let(::parseColor) ?: 0xFF000000.toInt(),
                )
            }
            val lists = json.getJSONObject("playlists")
            val playlists = lists.keys().asSequence().associateWith { name ->
                val ids = lists.getJSONArray(name)
                (0 until ids.length()).map { ids.getString(it) }
            }
            return SampleManifest(assets, broken, playlists)
        }
    }
}
