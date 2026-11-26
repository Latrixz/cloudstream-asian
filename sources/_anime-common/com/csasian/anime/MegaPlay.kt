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
 */
object MegaPlay {
    // Taken from the player's public script (lib/e1-player.min.js: SOURCE_ENC_KEY / SOURCE_ENC_IV).
    // They may rotate with a new player build; if links stop resolving, check that file first.
    private const val SOURCE_KEY = "i?LMTAx0Q6,:}50U"
    private const val SOURCE_IV = "W0;27ToaUpl_P%'c"

    private val mapper = ObjectMapper()

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
        files.forEach { file ->
            callback(
                newExtractorLink(source, label, file, ExtractorLinkType.M3U8) {
                    referer = "$origin/"
                    quality = Qualities.Unknown.value
                    headers = mapOf("User-Agent" to userAgent, "Origin" to origin)
                },
            )
        }
        return files.isNotEmpty()
    }

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
