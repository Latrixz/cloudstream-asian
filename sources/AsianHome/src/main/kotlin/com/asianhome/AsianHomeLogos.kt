package com.asianhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
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
 * Small logos for the settings screen. Everything is fetched on Dispatchers.IO, decoded down to
 * about 128px and kept in memory, so reopening the settings is instant. A failed URL is remembered
 * and not retried in this session; callers then show a letter badge instead.
 */
object AsianHomeLogos {
    const val REPO_LOGO = "https://raw.githubusercontent.com/Latrixz/cloudstream-asian/code/branding/logo-512.png"
    private const val TARGET_PX = 128
    private const val FETCH_TIMEOUT_MS = 8_000L
    private const val ICON_MAP_TIMEOUT_MS = 4_000L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bitmaps = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()

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
            if (map.isNotEmpty()) iconMap = map else synchronized(this@AsianHomeLogos) { iconMapJob = null }
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

    fun favicon(mainUrl: String): String? = try {
        URI(mainUrl).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?.let { "https://www.google.com/s2/favicons?domain=$it&sz=$TARGET_PX" }
    } catch (_: Throwable) {
        null
    }
}
