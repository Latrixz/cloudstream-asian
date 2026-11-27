package com.csasian.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI

/**
 * Source logos and "is this one of ours?" for the source switcher. Shared by every plugin
 * (compiled into each), so it only uses the app's own APIs. Logos are the plugin iconUrl from the
 * installed repositories, else the site's favicon. Everything is fetched on Dispatchers.IO,
 * decoded down to about 128px and kept in memory; a failed URL is not retried this session.
 * (AsianHome keeps its own copy of this logic in AsianHomeLogos.)
 */
object CsLogos {
    const val REPO_LOGO = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/logo-512.png"

    /** Our plugins are downloaded from URLs under this GitHub repo. */
    private const val REPO_MARKER = "latrixz/cloudstream-asian"

    /** Fallback when a plugin has no online record (e.g. a test copy installed by hand). */
    private val OUR_NAMES = setOf(
        "CS Asian 首页", "直播 Live TV", "华人影院 Huarw", "CN影院 Csxyy", "泥巴影院 Nbyy", "唐人街 Trjvod", "优视频 UVOD", "华视界 Haotv", "华视影院 Huavod", "七味网 Qwfilm", "南瓜影视 Nangua", "网飞猫 Ncat", "看看鸭 Kankanya", "看片狂人 Kpkuang", "可可影视 Kkys", "母鸡资源 Mujizy", "磁力熊 Cilixiong", "奈飞工厂 Netflixgc", "共青春影院 Gqc", "黑夜影院 Darkvod", "233动漫 Dm233", "321Movies CS", "Cinejoy CS", "ZXCStream CS", "ReelFren Shorts", "ChartDrama Shorts", "DramaTip Shorts", "DramaFren",
        "随便看 Suikan", "爱瓜TV Aigua", "低端影视 ddys", "独播库 Duboku", "冷映 Len", "酷播 Kubb",
        "小宝影院 Xiaobao", "欧乐影院 Olevod", "Movieffm", "Movy", "Crackle", "Kissasian", "frameXTV",
        "FlixVision", "Rivestream", "Rivo", "OneTouchTV Asia", "Kisskh Asia", "Donghuastream CS",
        "AniKoto CS", "AnimeSuge CS", "4KHDHub CS",
    )

    private const val TARGET_PX = 128
    private const val FETCH_TIMEOUT_MS = 8_000L
    private const val ICON_MAP_TIMEOUT_MS = 4_000L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bitmaps = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    // ---- Ours or not ----

    /** The installed (online) plugin record that registered [api], matched by its file path. */
    private fun pluginOf(api: MainAPI): PluginData? = try {
        val path = api.sourcePlugin ?: return null
        val name = File(path).name
        PluginManager.getPluginsOnline().firstOrNull { it.filePath == path || File(it.filePath).name == name }
    } catch (_: Throwable) {
        null
    }

    /**
     * True for sources from the cloudstream-asian repo: the plugin's download URL points at our repo.
     * When the app has no online record for the plugin, the known source names decide.
     */
    fun isOurs(api: MainAPI): Boolean {
        val url = pluginOf(api)?.url
        if (!url.isNullOrBlank()) return url.lowercase().contains(REPO_MARKER)
        return api.name in OUR_NAMES
    }

    // ---- SkyStream (desktop) ----

    /**
     * True when the plugin runs outside Android, e.g. in SkyStream's desktop engine. There, screens
     * are redrawn by the host app: images can only be given as a URL (ImageView.setImageURI), and
     * bitmaps can't be decoded, so logos go by URL instead.
     */
    /** True only for SkyStream's desktop engine (a plain JVM: no Android WebView there). */
    val onDesktop: Boolean by lazy {
        val vm = System.getProperty("java.vm.name").orEmpty()
        !vm.contains("Dalvik", true) && !vm.contains("ART", false)
    }

    val onSkyStream: Boolean by lazy {
        val desktop = onDesktop
        // SkyStream's Android app runs plugins on Android itself, so recognise it by its package name.
        val skyAndroid = try {
            com.lagradost.cloudstream3.CloudStreamApp.context?.packageName.orEmpty().contains("skystream", true)
        } catch (_: Throwable) {
            false
        }
        desktop || skyAndroid
    }

    private val goodUrls = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** The first candidate URL that serves an image (checked by its first bytes), or null. */
    suspend fun firstImageUrl(urls: List<String>): String? {
        for (url in urls.distinct()) {
            val ok = goodUrls[url] ?: try {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    val res = app.get(url)
                    res.isSuccessful && looksLikeImage(res.body.bytes())
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }.also { goodUrls[url] = it }
            if (ok) return url
        }
        return null
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun at(i: Int) = b[i].toInt() and 0xff
        return (at(0) == 0x89 && at(1) == 0x50) || // PNG
            (at(0) == 0xff && at(1) == 0xd8) || // JPEG
            (at(0) == 0x47 && at(1) == 0x49) || // GIF
            (at(0) == 0x52 && at(1) == 0x49 && at(8) == 0x57) || // WEBP
            (at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0) // ICO
    }

    // ---- Bitmaps ----

    fun cached(url: String): Bitmap? = bitmaps.get(url)

    /** The first candidate URL that loads, or null when none do. */
    suspend fun firstBitmap(urls: List<String>): Bitmap? {
        for (url in urls.distinct()) {
            bitmaps.get(url)?.let { return it }
            if (url in failed) continue
            val job = synchronized(inFlight) { inFlight.getOrPut(url) { io.async { fetch(url) } } }
            val bmp = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) return bmp
        }
        return null
    }

    private suspend fun fetch(url: String): Bitmap? {
        val bmp = try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                val res = app.get(url)
                if (!res.isSuccessful) null else decode(res.body.bytes())
            }
        } catch (_: Throwable) {
            null
        }
        if (bmp != null) bitmaps.put(url, bmp) else failed += url
        synchronized(inFlight) { inFlight.remove(url) }
        return bmp
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- Which URLs to try for a source ----

    /** Plugin icons from the installed repositories, keyed by plugin file path and file name. */
    @Volatile
    private var iconMap: Map<String, String>? = null
    private var iconMapJob: Deferred<Map<String, String>>? = null

    /** Candidate logo URLs for a source: its plugin iconUrl if known, then its site's favicon. */
    suspend fun candidates(api: MainAPI): List<String> {
        val icons = iconMap ?: withTimeoutOrNull(ICON_MAP_TIMEOUT_MS) { loadIconMap().await() } ?: emptyMap()
        val plugin = try {
            api.sourcePlugin?.let { path -> icons[path] ?: icons[File(path).name] }
        } catch (_: Throwable) {
            null
        }
        return listOfNotNull(plugin?.let(::sized), favicon(api.mainUrl))
    }

    private fun loadIconMap(): Deferred<Map<String, String>> = synchronized(this) {
        iconMapJob ?: io.async {
            val map = try {
                buildIconMap()
            } catch (_: Throwable) {
                emptyMap()
            }
            // Keep a good result for the session; an empty one is retried next time.
            if (map.isNotEmpty()) iconMap = map else synchronized(this@CsLogos) { iconMapJob = null }
            map
        }.also { iconMapJob = it }
    }

    private suspend fun buildIconMap(): Map<String, String> {
        val byUrl = mutableMapOf<String, String>()
        val byInternal = mutableMapOf<String, String>()
        RepositoryManager.getRepositories().forEach { repo ->
            val plugins = try {
                RepositoryManager.getRepoPlugins(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            plugins?.forEach { wrapper ->
                val site = wrapper.plugin
                val icon = site.iconUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                byUrl[site.url] = icon
                byInternal[site.internalName] = icon
            }
        }
        val result = mutableMapOf<String, String>()
        PluginManager.getPluginsOnline().forEach { data ->
            val icon = byUrl[data.url] ?: byInternal[data.internalName] ?: return@forEach
            result[data.filePath] = icon
            result[File(data.filePath).name] = icon
        }
        return result
    }

    private fun sized(url: String) = url.replace("%size%", "$TARGET_PX").replace("%exact_size%", "$TARGET_PX")

    private fun favicon(mainUrl: String): String? = try {
        URI(mainUrl).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?.let { "https://www.google.com/s2/favicons?domain=$it&sz=$TARGET_PX" }
    } catch (_: Throwable) {
        null
    }
}
