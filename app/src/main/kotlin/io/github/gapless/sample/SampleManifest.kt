package io.github.gapless.sample

import android.content.Context
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessFit
import org.json.JSONObject

/** An asset and how long the sample app shows it. */
data class SampleItem(val asset: GaplessAsset, val durationMs: Long) {
    /** The item with a video's volume set to [volume]; other assets have none. */
    fun withVolume(volume: Float): SampleItem =
        if (asset is GaplessAsset.Video) copy(asset = asset.copy(volume = volume)) else this
}

/**
 * The generated test media in assets/samples, described by samples.json. See samples/README.md
 * for what each asset and playlist is for.
 */
class SampleManifest(
    private val items: Map<String, SampleItem>,
    private val broken: Set<String>,
    val playlists: Map<String, List<String>>,
) {

    /**
     * The items of [name] in play order, or null if there is no such playlist. Without [allowUhd],
     * working videos larger than Full HD are left out; images and broken assets stay in.
     */
    fun playlist(name: String, volume: Float, allowUhd: Boolean): List<SampleItem>? =
        playlists[name]?.let { items(it, volume, allowUhd) }

    /** The named items in order, or null if any id is unknown. */
    fun items(ids: List<String>, volume: Float, allowUhd: Boolean): List<SampleItem>? =
        ids.takeIf { it.all(items::containsKey) }
            ?.map { id -> items.getValue(id) }
            ?.filter { allowUhd || it.asset.id in broken || !it.asset.isUhdVideo() }
            ?.map { it.withVolume(volume) }

    /** The same media with every page script and readiness wait removed, for comparison. */
    fun withoutScripts(): SampleManifest = mapAssets { a ->
        if (a is GaplessAsset.Web) a.copy(javaScript = null, waitForPageReady = false) else a
    }

    /** The same media with every asset given [fit] and [backgroundColor], where set. */
    fun framed(fit: GaplessFit?, backgroundColor: Int?): SampleManifest = mapAssets { a ->
        val f = fit ?: a.fit
        val bg = backgroundColor ?: a.backgroundColor
        when (a) {
            is GaplessAsset.Video -> a.copy(fit = f, backgroundColor = bg)
            is GaplessAsset.Image -> a.copy(fit = f, backgroundColor = bg)
            is GaplessAsset.Web -> a.copy(fit = f, backgroundColor = bg)
        }
    }

    /**
     * The same media fetched from [server], an HTTP server serving the samples folder, instead of
     * from the APK, with [header] (`Name: value`) sent with every request. For testing networked
     * playback and request headers. Websites keep their own addresses.
     */
    fun served(server: String?, header: String?): SampleManifest {
        if (server == null && header == null) return this
        val base = server?.let { if (it.endsWith("/")) it else "$it/" }
        val extra = header?.let {
            val name = it.substringBefore(':').trim()
            require(name.isNotEmpty() && ':' in it) { "Header \"$it\" isn't Name: value" }
            mapOf(name to it.substringAfter(':').trim())
        }.orEmpty()
        return mapAssets { a ->
            val uri = if (base != null && a.uri.startsWith(ASSET_PREFIX)) base + a.uri.removePrefix(ASSET_PREFIX) else a.uri
            val headers = a.requestHeaders + extra
            when (a) {
                is GaplessAsset.Video -> a.copy(uri = uri, requestHeaders = headers)
                is GaplessAsset.Image -> a.copy(uri = uri, requestHeaders = headers)
                is GaplessAsset.Web -> a.copy(uri = uri, requestHeaders = headers)
            }
        }
    }

    private fun mapAssets(transform: (GaplessAsset) -> GaplessAsset) = SampleManifest(
        items.mapValues { (_, item) -> item.copy(asset = transform(item.asset)) },
        broken,
        playlists,
    )

    private fun GaplessAsset.isUhdVideo(): Boolean {
        if (this !is GaplessAsset.Video) return false
        val w = width ?: return false
        val h = height ?: return false
        return maxOf(w, h) > 1920 || minOf(w, h) > 1080
    }

    companion object {
        const val PATH = "samples/samples.json"
        private const val ASSET_PREFIX = "file:///android_asset/samples/"

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

        private fun parseAsset(id: String, a: JSONObject): GaplessAsset {
            val uri = a.getString("uri")
            val mimeType = a.getString("mimeType")
            val width = a.optInt("width").takeIf { it > 0 }
            val height = a.optInt("height").takeIf { it > 0 }
            val fit = a.optString("fit").takeIf { it.isNotEmpty() }?.let(::parseFit) ?: GaplessFit.Contain
            val background = a.optString("background").takeIf { it.isNotEmpty() }
                ?.let(::parseColor) ?: 0xFF000000.toInt()
            return when (GaplessAsset.kindOf(mimeType)) {
                GaplessAsset.Kind.Video -> GaplessAsset.Video(
                    id, uri, mimeType = mimeType, width = width, height = height,
                    // Clipped to its slot, so a longer clip ends where the slot does.
                    durationMs = a.getLong("durationMs"),
                    fit = fit, backgroundColor = background,
                )
                GaplessAsset.Kind.Image -> GaplessAsset.Image(
                    id, uri, animationLoops = a.optInt("animationLoops").takeIf { it > 0 },
                    fit = fit, backgroundColor = background,
                )
                GaplessAsset.Kind.Web -> GaplessAsset.Web(
                    id, uri, viewportWidth = width, viewportHeight = height,
                    javaScript = a.optString("javaScript").takeIf { it.isNotEmpty() },
                    waitForPageReady = a.optBoolean("waitForPageReady"),
                    fit = fit, backgroundColor = background,
                )
                null -> throw IllegalArgumentException("Sample \"$id\" has unsupported mimeType \"$mimeType\"")
            }
        }

        fun load(context: Context): SampleManifest {
            val json = JSONObject(context.assets.open(PATH).bufferedReader().use { it.readText() })
            val list = json.getJSONArray("assets")
            val broken = mutableSetOf<String>()
            val assets = (0 until list.length()).associate { i ->
                val a = list.getJSONObject(i)
                val id = a.getString("id")
                if (a.optBoolean("broken")) broken += id
                id to SampleItem(parseAsset(id, a), a.getLong("durationMs"))
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
