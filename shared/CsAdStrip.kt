package com.csasian.shared

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Removes the ad clips that Chinese supply networks (1080zyk/yzzy, bfzy, modu, lzzy, ...) splice into
 * HLS media playlists. Use it from a provider with
 * `override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = CsAdStrip.interceptor`.
 *
 * Only VOD media playlists (#EXTM3U + #EXTINF + #EXT-X-ENDLIST) are touched; master playlists, segments
 * and anything unexpected pass through unchanged. The rules are deliberately conservative:
 *  1. Blocks (split at #EXT-X-DISCONTINUITY) whose segments all come from another host/directory than
 *     the "main" one (the directory holding most of the duration) are dropped, if they are short and
 *     that other directory holds only a small share of the whole playlist (modu, lzzy).
 *  2. Segments whose path has an ad marker (/adjump/, /ad/, /ads/) are dropped (bfzy).
 *  3. Same-directory blocks of 10-20 s whose segment durations exactly match a known ad clip are
 *     dropped, except at the very start (yzzy / 1080zyk, dyttzy).
 * If more than a quarter of the playlist would go, or anything looks odd, the original is returned.
 */
object CsAdStrip {
    private const val MAX_BYTES = 4_000_000L
    private val AD_PATH = Regex("""/(adjump|ads?)/""")

    /** Segment durations of the known same-folder ad clips (yzzy / 1080zyk, dyttzy). */
    private val AD_PATTERNS = listOf(
        doubleArrayOf(6.633333, 3.333333, 4.8, 1.7),
        doubleArrayOf(5.933333, 3.333333, 2.8, 5.3, 0.3),
        doubleArrayOf(5.566667, 2.933333, 5.7, 3.333333, 1.533333),
        doubleArrayOf(6.666667, 2.133333, 3.233333, 5.433333),
        // ffzy (非凡)
        doubleArrayOf(4.866667, 3.333333, 5.6, 2.866667, 2.966667),
        doubleArrayOf(6.033333, 3.333333, 5.766667, 2.233333),
    )

    val interceptor: Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        clean(response)
    }

    private fun clean(response: Response): Response {
        try {
            if (!response.isSuccessful) return response
            val type = response.header("Content-Type").orEmpty().lowercase()
            val path = response.request.url.encodedPath.lowercase()
            if (!type.contains("mpegurl") && !path.endsWith(".m3u8") && !path.endsWith(".m3u")) {
                if (type.startsWith("video/") || type.startsWith("audio/")) return response
                val head = response.peekBody(64).string().trimStart('﻿', ' ', '\r', '\n', '\t')
                if (!head.startsWith("#EXTM3U")) return response
            }
            if (response.body.contentLength() > MAX_BYTES) return response
            val peeked = response.peekBody(MAX_BYTES + 1)
            if (peeked.contentLength() > MAX_BYTES) return response
            val rewritten = analyze(peeked.string(), response.request.url.toString())?.text ?: return response
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            val mediaType = response.body.contentType() ?: "application/vnd.apple.mpegurl".toMediaTypeOrNull()
            val cleaned = response.newBuilder()
                .removeHeader("Content-Encoding")
                .header("Content-Length", bytes.size.toString())
                .body(bytes.toResponseBody(mediaType))
                .build()
            runCatching { response.body.close() }
            return cleaned
        } catch (_: Throwable) {
            return response
        }
    }

    private class Segment(
        val tags: List<String>,
        val uri: String,
        val duration: Double,
        val dir: String,
        val path: String,
        val key: String?,
        val map: String?,
        val sequence: Long,
    ) {
        var dropReason: String? = null
    }

    private class Block(val hadDiscontinuity: Boolean) {
        val segments = mutableListOf<Segment>()
        val duration get() = segments.sumOf { it.duration }
    }

    /** Result of [analyze]: the rewritten playlist and what was removed (seconds per reason). */
    internal class Analysis(val text: String, val total: Double, val dropped: Map<String, Double>, val blocks: List<String>)

    /** Returns the cleaned playlist, or null to keep the original (nothing to remove, or any doubt). */
    internal fun analyze(text: String, baseUrl: String): Analysis? = try {
        analyzeOrNull(text, baseUrl)
    } catch (_: Throwable) {
        null
    }

    private fun analyzeOrNull(text: String, baseUrl: String): Analysis? {
        val content = text.trimStart('﻿', ' ', '\r', '\n', '\t')
        if (!content.startsWith("#EXTM3U")) return null
        if (!content.contains("#EXTINF") || content.contains("#EXT-X-STREAM-INF")) return null
        if (!content.contains("#EXT-X-ENDLIST")) return null
        if (content.contains("#EXT-X-BYTERANGE")) return null
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val lines = content.lines()

        val firstBody = lines.indexOfFirst { line ->
            val t = line.trim()
            t.startsWith("#EXTINF") || t.startsWith("#EXT-X-DISCONTINUITY") && !t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ||
                t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP") || t.startsWith("#EXT-X-PROGRAM-DATE-TIME") ||
                (t.isNotEmpty() && !t.startsWith("#"))
        }
        val lastUri = lines.indexOfLast { it.trim().let { t -> t.isNotEmpty() && !t.startsWith("#") } }
        if (firstBody < 0 || lastUri < firstBody) return null
        val header = lines.subList(0, firstBody)
        val trailer = lines.subList(lastUri + 1, lines.size)
        var sequence = header.firstOrNull { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L

        val blocks = mutableListOf(Block(false))
        var pending = mutableListOf<String>()
        var duration = -1.0
        var key: String? = null
        var map: String? = null
        for (raw in lines.subList(firstBody, lastUri + 1)) {
            val t = raw.trim()
            when {
                t.isEmpty() -> Unit
                t.startsWith("#EXT-X-DISCONTINUITY") && !t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") ->
                    blocks.add(Block(true))
                t.startsWith("#EXT-X-KEY:") -> key = t
                t.startsWith("#EXT-X-MAP:") -> map = t
                t.startsWith("#EXTINF:") -> {
                    duration = t.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: return null
                    pending.add(t)
                }
                t.startsWith("#") -> pending.add(t)
                else -> {
                    if (duration < 0) return null
                    val url = base.resolve(t) ?: return null
                    val dir = "${url.scheme}://${url.host}:${url.port}${url.encodedPath.substringBeforeLast('/')}"
                    blocks.last().segments.add(Segment(pending, t, duration, dir, url.encodedPath, key, map, sequence++))
                    pending = mutableListOf()
                    duration = -1.0
                }
            }
        }
        val segments = blocks.flatMap { it.segments }
        val total = segments.sumOf { it.duration }
        if (segments.isEmpty() || total <= 0) return null

        // 2. Ad marker in the path (bfzy: /video/adjump/time/...).
        segments.forEach { if (AD_PATH.containsMatchIn(it.path)) it.dropReason = "marker" }

        // 1. Short blocks served entirely from a minor, foreign host/directory (modu, lzzy).
        val dirDuration = segments.groupBy { it.dir }.mapValues { (_, list) -> list.sumOf { it.duration } }
        val main = dirDuration.maxByOrNull { it.value }!!
        if (main.value >= 0.7 * total) {
            blocks.forEach { block ->
                val dirs = block.segments.map { it.dir }.toSet()
                if (dirs.isEmpty() || main.key in dirs || block.duration > 60) return@forEach
                if (dirs.sumOf { dirDuration[it] ?: 0.0 } > 0.15 * total) return@forEach
                block.segments.forEach { if (it.dropReason == null) it.dropReason = "foreign" }
            }
        }

        // 3. Known same-folder ad clips (yzzy / 1080zyk), never the opening block.
        val firstReal = blocks.indexOfFirst { it.segments.isNotEmpty() }
        blocks.forEachIndexed { index, block ->
            if (index == firstReal || block.segments.isEmpty()) return@forEachIndexed
            val length = block.duration
            if (length < 10 || length > 20) return@forEachIndexed
            val durations = block.segments.map { it.duration }
            val matches = AD_PATTERNS.any { pattern ->
                pattern.size == durations.size && pattern.indices.all { kotlin.math.abs(pattern[it] - durations[it]) < 0.02 }
            }
            if (matches) block.segments.forEach { if (it.dropReason == null) it.dropReason = "pattern" }
        }

        val removed = segments.filter { it.dropReason != null }
        if (removed.isEmpty()) return null
        val droppedTotal = removed.sumOf { it.duration }
        if (droppedTotal > 0.25 * total) return null

        val out = StringBuilder(text.length)
        header.forEach { out.append(it).append('\n') }
        var lastKey: String? = null
        var lastMap: String? = null
        for (block in blocks) {
            val kept = block.segments.filter { it.dropReason == null }
            if (kept.isEmpty()) continue
            if (block.hadDiscontinuity) out.append("#EXT-X-DISCONTINUITY\n")
            for (segment in kept) {
                val keyLine = segment.key?.let { explicitIv(it, segment.sequence) }
                if (keyLine != lastKey) {
                    out.append(keyLine ?: "#EXT-X-KEY:METHOD=NONE").append('\n')
                    lastKey = keyLine
                }
                if (segment.map != null && segment.map != lastMap) {
                    out.append(segment.map).append('\n')
                    lastMap = segment.map
                }
                segment.tags.forEach { out.append(it).append('\n') }
                out.append(segment.uri).append('\n')
            }
        }
        trailer.forEach { out.append(it).append('\n') }

        val dropped = removed.groupBy { it.dropReason!! }.mapValues { (_, list) -> list.sumOf { it.duration } }
        val summary = blocks.filter { it.segments.isNotEmpty() }.map { block ->
            val reason = block.segments.mapNotNull { it.dropReason }.distinct().joinToString("+")
            "%.2f%s".format(block.duration, if (reason.isEmpty()) "" else "[-$reason]")
        }
        return Analysis(out.toString().trimEnd('\n') + "\n", total, dropped, summary)
    }

    /**
     * Without an IV attribute, AES-128 segments use their media sequence number as IV; removing segments
     * renumbers them, so the original number is written out explicitly.
     */
    private fun explicitIv(keyLine: String, sequence: Long): String {
        val method = Regex("""METHOD=([A-Z0-9-]+)""").find(keyLine)?.groupValues?.get(1)
        if (method == null || method == "NONE" || keyLine.contains("IV=")) return keyLine
        return keyLine + ",IV=0x" + java.lang.Long.toHexString(sequence).padStart(32, '0')
    }
}
