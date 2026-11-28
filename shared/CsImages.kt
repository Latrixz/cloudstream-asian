package com.csasian.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A small poster loader for screens a plugin draws itself (CloudStream's own image loader isn't
 * available to plugins). Like CsLogos: downloads on Dispatchers.IO through the app's HTTP client,
 * decodes down to about the size shown (RGB_565, so a poster costs half the memory), and keeps
 * the results in a shared in-memory cache. At most [PARALLEL] downloads run at once, a URL that
 * failed isn't retried this session, and nothing here throws.
 *
 *   `CsImages.bind(scope, imageView, url, widthPx)` shows the image when it arrives (only if the
 *   view still wants that URL), `CsImages.clear(imageView)` lets the bitmap go again.
 */
object CsImages {
    private const val PARALLEL = 6
    private const val FETCH_TIMEOUT_MS = 15_000L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    private fun key(url: String, widthPx: Int) = "$widthPx|$url"

    fun cached(url: String, widthPx: Int): Bitmap? = cache.get(key(url, widthPx))

    /** The image at [url] decoded to roughly [widthPx] wide, or null if it can't be loaded. */
    suspend fun load(url: String, widthPx: Int, headers: Map<String, String> = emptyMap()): Bitmap? {
        val k = key(url, widthPx)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        val job = synchronized(inFlight) { inFlight.getOrPut(k) { io.async { fetch(url, widthPx, headers, k) } } }
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun fetch(url: String, widthPx: Int, headers: Map<String, String>, k: String): Bitmap? {
        val bmp = try {
            gate.withPermit {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    val res = app.get(url, headers = headers)
                    if (!res.isSuccessful) null else decode(res.body.bytes(), widthPx)
                }
            }
        } catch (_: Throwable) {
            null
        }
        if (bmp != null) cache.put(k, bmp) else failed += k
        synchronized(inFlight) { inFlight.remove(k) }
        return bmp
    }

    fun decode(bytes: ByteArray, widthPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Shows [url] in [view]: at once when cached, otherwise when it arrives ([headers] for the
     * download, e.g. a poster's Referer). [onLoaded] runs on the main thread after the bitmap is set (e.g. to hide a placeholder). Returns the job, or null.
     */
    fun bind(
        scope: CoroutineScope,
        view: ImageView,
        url: String?,
        widthPx: Int,
        headers: Map<String, String> = emptyMap(),
        onLoaded: ((Bitmap) -> Unit)? = null,
    ): Job? {
        view.tag = url
        if (url.isNullOrBlank()) {
            view.setImageDrawable(null)
            return null
        }
        cached(url, widthPx)?.let {
            view.setImageBitmap(it)
            onLoaded?.invoke(it)
            return null
        }
        view.setImageDrawable(null)
        return scope.launch {
            val bmp = load(url, widthPx, headers) ?: return@launch
            if (view.tag == url) {
                view.setImageBitmap(bmp)
                onLoaded?.invoke(bmp)
            }
        }
    }

    /** Lets go of the view's bitmap (it stays in the cache while there is room). */
    fun clear(view: ImageView) {
        view.tag = null
        view.setImageDrawable(null)
    }
}
