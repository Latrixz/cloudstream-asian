package com.csasian.anime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay embed player (megaplay.buzz, also served from mirrors such as vidtube.site).
 *
 *  - Embed page: {origin}/stream/s-2/{realId}/{sub|dub}[?s=cdn]  (or /videojs/stream/...).
 *    The player element carries data-id="{episodeId}".
 *  - Sources:    {base}stream/getSources?id={episodeId}[&s=cdn] (base = origin + "/" or origin + "/videojs/")
 *    returns { tracks: [vtt subtitles], intro, outro, enc }. "enc" is base64url AES-256-CBC of
 *    {"file": ".../master.m3u8"}; key and IV are plain strings zero-padded to 32 / 16 bytes.
 *  - The playlist and segments (named .jpg, really MPEG-TS) answer 403 without a Referer from the
 *    player's origin.
 *  - Some servers (seen on "HD-1", megap.*) list segments hosted on image CDNs (tiktokcdn / ibyteimg)
 *    where each segment is a real PNG with the MPEG-TS appended after it. Browsers' hls.js copes,
 *    ExoPlayer fails with "decoding failed", so variants with image segments are dropped.
 *  - Masters with several renditions (1080p/720p/480p) are split into one link per rendition (not
 *    when the sound is in separate audio renditions, which only the master carries). The
 *    1080p ones are often H.264 High@5.0, above some TV hardware decoders (High@4.2); ExoPlayer's
 *    adaptive switch into them fails mid-playback, so on such devices they are ranked last.
 */
object MegaPlay {
    // Taken from the player's public script (lib/e1-player.min.js: SOURCE_ENC_KEY / SOURCE_ENC_IV).
    // They may rotate with a new player build; if links stop resolving, check that file first.
    private const val SOURCE_KEY = "i?LMTAx0Q6,:}50U"
    private const val SOURCE_IV = "W0;27ToaUpl_P%'c"

    private val mapper = ObjectMapper()
    private const val PROBE_TIMEOUT_S = 8L
    private const val PROBE_BUDGET_MS = 15_000L

    fun handles(url: String): Boolean =
        Regex("""/stream/s-\d+/\d+/""").containsMatchIn(url)

    /**
     * Resolves one embed URL into an HLS link (+ its subtitles).
     * [source] is the provider name, [label] the server label shown to the user.
     */
    suspend fun extract(
        embedUrl: String,
        source: String,
        label: String,
        siteReferer: String,
        userAgent: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val uri = URI(embedUrl)
        val origin = "${uri.scheme}://${uri.host}"
        val html = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to siteReferer)).text
        val episodeId = Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1) ?: return false
        val base = if (uri.path.startsWith("/videojs/")) "$origin/videojs/" else "$origin/"
        val cdn = uri.query.orEmpty().split('&')
            .firstOrNull { it.startsWith("s=") }?.substringAfter("s=")?.takeIf { it.isNotBlank() }

        val api = buildString {
            append(base).append("stream/getSources?id=").append(episodeId)
            if (cdn != null) append("&s=").append(cdn)
        }
        val body = app.get(
            api,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to embedUrl,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        ).text
        val json = mapper.readTree(body)

        json.path("tracks").forEach { track ->
            val kind = track.path("kind").asText("")
            val file = track.path("file").asText("")
            if ((kind == "captions" || kind == "subtitles") && file.startsWith("http")) {
                subtitleCallback(newSubtitleFile(track.path("label").asText("").ifBlank { "English" }, file))
            }
        }

        val files = streamFiles(json.path("sources")).ifEmpty {
            val enc = json.path("enc").asText("")
            if (enc.isBlank()) emptyList() else streamFiles(mapper.readTree(decrypt(enc)))
        }
        val linkHeaders = mapOf("User-Agent" to userAgent, "Origin" to origin, "Referer" to "$origin/")
        val maxLevel = avcMaxLevel
        val variants = coroutineScope {
            files.map { file ->
                async {
                    runCatching { withTimeoutOrNull(PROBE_BUDGET_MS) { variantsOf(file, linkHeaders, maxLevel) } }
                        .getOrNull()
                }
            }
                .awaitAll()
        }
        var offered = 0
        files.forEachIndexed { i, file ->
            val list = variants[i]
            if (list == null) { // probing failed: offer the playlist as it is
                callback(hlsLink(source, label, file, Qualities.Unknown.value, origin, userAgent))
                offered++
                return@forEachIndexed
            }
            val single = list.size == 1
            list.filter { !it.images }.forEach { v ->
                // One link per variant, so the player never switches into a variant it can't decode.
                val url = if (single) file else v.url
                val name = if (single || v.height == null) label else "$label ${v.height}p"
                val quality = when {
                    // Above what this device's hardware decoder can do: still offered, but last
                    // (when every link is like that, e.g. single-rendition 1080p titles, nothing is lost).
                    v.tooHigh -> Qualities.P144.value
                    else -> v.height ?: Qualities.Unknown.value
                }
                callback(hlsLink(source, name, url, quality, origin, userAgent))
                offered++
            }
        }
        return offered > 0
    }

    private suspend fun hlsLink(source: String, name: String, url: String, quality: Int, origin: String, userAgent: String) =
        newExtractorLink(source, name, url, ExtractorLinkType.M3U8) {
            this.referer = "$origin/"
            this.quality = quality
            this.headers = mapOf("User-Agent" to userAgent, "Origin" to origin)
        }

    private class Variant(val url: String, val height: Int?, val images: Boolean, val tooHigh: Boolean)

    /**
     * Expands a master playlist into its variants and probes each one: a spread of segments (first,
     * quarter points, last; 16 bytes each) for image-wrapped data, and the first segment's H.264 SPS
     * for its level. A media playlist comes back as a single variant.
     */
    private suspend fun variantsOf(playlistUrl: String, headers: Map<String, String>, maxLevel: Int?): List<Variant> {
        val text = app.get(playlistUrl, headers = headers, timeout = PROBE_TIMEOUT_S).text
        if (!text.contains("#EXT-X-STREAM-INF")) {
            return listOf(probeVariant(playlistUrl, text, null, headers, maxLevel))
        }
        val lines = text.lines().map { it.trim() }
        val entries = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return@mapIndexedNotNull null
            val height = Regex("""NAME="(\d{3,4})p"""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            URI(playlistUrl).resolve(uri).toString() to height
        }.distinctBy { it.first }
        val probed = coroutineScope {
            entries.map { (url, height) ->
                async {
                    runCatching {
                        val media = app.get(url, headers = headers, timeout = PROBE_TIMEOUT_S).text
                        probeVariant(url, media, height, headers, maxLevel)
                    }.getOrElse { Variant(url, height, images = false, tooHigh = false) } // keep on error
                }
            }.awaitAll()
        }
        // Sound in separate audio renditions (#EXT-X-MEDIA:TYPE=AUDIO, e.g. Japanese and English on
        // newer episodes) is only reachable through the master: a variant on its own plays silent.
        // Such a master stays one link; the player picks the audio track and adapts the quality.
        if (SEPARATE_AUDIO.containsMatchIn(text)) {
            val usable = probed.filter { !it.images }
            if (usable.isEmpty()) return listOf(Variant(playlistUrl, null, images = true, tooHigh = false))
            return listOf(
                Variant(
                    playlistUrl,
                    usable.mapNotNull { it.height }.maxOrNull(),
                    images = false,
                    tooHigh = usable.all { it.tooHigh },
                ),
            )
        }
        return probed
    }

    private val SEPARATE_AUDIO = Regex("#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=")

    private suspend fun probeVariant(
        url: String,
        media: String,
        height: Int?,
        headers: Map<String, String>,
        maxLevel: Int?,
    ): Variant {
        val segments = media.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { URI(url).resolve(it).toString() }
        if (segments.isEmpty()) return Variant(url, height, images = false, tooHigh = false)
        val n = segments.size
        val picks = listOf(0, n / 4, n / 2, 3 * n / 4, n - 1).distinct()
        val heads = coroutineScope {
            picks.map { i ->
                async {
                    // First segment: enough bytes to reach the SPS. Others: just the header.
                    runCatching { firstBytes(segments[i], headers, if (i == 0) 16384 else 16) }.getOrNull()
                }
            }.awaitAll()
        }
        val images = heads.any { it != null && isImage(it) }
        val level = heads.firstOrNull()?.let { avcLevel(it) }
        val tooHigh = level != null && maxLevel != null && level > maxLevel
        return Variant(url, height, images, tooHigh)
    }

    private suspend fun firstBytes(url: String, headers: Map<String, String>, count: Int): ByteArray {
        val res = app.get(url, headers = headers + ("Range" to "bytes=0-${count - 1}"), timeout = PROBE_TIMEOUT_S)
        val stream = res.okhttpResponse.body?.byteStream() ?: return ByteArray(0)
        return stream.use { input ->
            val buf = ByteArray(count)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
    }

    private fun isImage(b: ByteArray): Boolean {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) || // PNG
            (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) || // JPEG
            (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38) || // GIF8
            (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50) // RIFF....WEBP
    }

    /** level_idc (e.g. 40, 42, 50) of the first H.264 SPS in the MPEG-TS bytes, or null. */
    private fun avcLevel(ts: ByteArray): Int? {
        // Join the TS packet payloads so a NAL header split across packets is still found.
        val start = (0 until minOf(188, ts.size)).firstOrNull { s ->
            (0..2).all { k -> s + 188 * k < ts.size && ts[s + 188 * k] == 0x47.toByte() }
        } ?: return null
        val payload = java.io.ByteArrayOutputStream()
        var p = start
        while (p + 188 <= ts.size && ts[p] == 0x47.toByte()) {
            val afc = (ts[p + 3].toInt() shr 4) and 0x3
            var off = 4
            if (afc == 2 || afc == 3) off += 1 + (ts[p + 4].toInt() and 0xFF)
            if ((afc == 1 || afc == 3) && off < 188) payload.write(ts, p + off, 188 - off)
            p += 188
        }
        val b = payload.toByteArray()
        for (i in 0 until b.size - 6) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte() &&
                (b[i + 3].toInt() and 0x1F) == 7
            ) {
                val profile = b[i + 4].toInt() and 0xFF
                if (profile in setOf(66, 77, 88, 100, 110, 122, 144, 244)) return b[i + 6].toInt() and 0xFF
            }
        }
        return null
    }

    /**
     * Highest H.264 High-profile level (as level_idc, e.g. 42) of this device's hardware decoders, or
     * null when unknown (not on Android, no hardware decoder listed). ExoPlayer picks the hardware
     * decoder even when a stream's level is above it, and some TV decoders then fail mid-playback
     * (seen: MStar OMX.MS.AVC.Decoder, High/4.2, on 1080p High/5.0 streams).
     */
    private val avcMaxLevel: Int? by lazy {
        runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } &&
                    (android.os.Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated)
            }.mapNotNull { info ->
                info.getCapabilitiesForType("video/avc").profileLevels
                    .filter { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
                    .maxOfOrNull { it.level }
            }.maxOrNull()?.let { levelIdc(it) }
        }.getOrNull()
    }

    private fun levelIdc(androidLevel: Int): Int? = mapOf(
        0x1 to 10, 0x2 to 9, 0x4 to 11, 0x8 to 12, 0x10 to 13, 0x20 to 20, 0x40 to 21, 0x80 to 22,
        0x100 to 30, 0x200 to 31, 0x400 to 32, 0x800 to 40, 0x1000 to 41, 0x2000 to 42,
        0x4000 to 50, 0x8000 to 51, 0x10000 to 52, 0x20000 to 60, 0x40000 to 61, 0x80000 to 62,
    )[androidLevel]

    /** {"file": url}, [{"file": url}, ...] or a plain url string. */
    private fun streamFiles(node: JsonNode): List<String> = when {
        node.isArray -> node.mapNotNull { it.path("file").asText("").takeIf { f -> f.startsWith("http") } }
        node.isObject -> listOfNotNull(node.path("file").asText("").takeIf { it.startsWith("http") })
        node.isTextual -> listOfNotNull(node.asText().takeIf { it.startsWith("http") })
        else -> emptyList()
    }.distinct()

    private fun decrypt(enc: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(padded(SOURCE_KEY, 32), "AES"),
            IvParameterSpec(padded(SOURCE_IV, 16)),
        )
        return String(cipher.doFinal(base64Url(enc)), Charsets.UTF_8)
    }

    private fun padded(text: String, size: Int): ByteArray =
        text.toByteArray(Charsets.UTF_8).copyOf(size)

    private fun base64Url(text: String): ByteArray {
        var std = text.trim().replace('-', '+').replace('_', '/')
        while (std.length % 4 != 0) std += "="
        return android.util.Base64.decode(std, android.util.Base64.DEFAULT)
    }
}
