package com.csasian.shared

import com.lagradost.cloudstream3.SearchQuality

/**
 * Maps a site's own card label ("HD", "1080P", "蓝光", "4K", "TC", "抢先版", "BluRay", ...) to a poster
 * quality badge. Returns null for anything that isn't a quality label, e.g. episode status like
 * "更新至第10集", "全40集", "完结", "EP 12" — never invent a badge the site doesn't show.
 */
object CsQuality {
    fun from(label: String?): SearchQuality? {
        val raw = label?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val t = raw.lowercase()
        val words = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        fun has(vararg w: String) = w.any { it in words }
        return when {
            // Cam / theatre rips first: "TC抢先版", "HDCAM", "枪版" must not read as HD.
            "hdcam" in t || "hd-cam" in t -> SearchQuality.HdCam
            "camrip" in t -> SearchQuality.CamRip
            has("cam", "tc", "hdtc") || words.any { Regex("tc(v\\d+)?").matches(it) } || "枪版" in t || "槍版" in t || "抢先" in t || "搶先" in t -> SearchQuality.Cam
            has("ts", "hdts", "telesync") || "hd-ts" in t -> SearchQuality.Telesync
            "4k" in t || "2160" in t || "uhd" in words -> SearchQuality.FourK
            has("hdr", "hdr10", "dv", "dovi") || "dolby vision" in t || "杜比视界" in t -> SearchQuality.HDR
            "bluray" in t || "blu-ray" in t || "blueray" in t || has("bd", "bdrip", "remux") || "蓝光" in t || "藍光" in t ->
                SearchQuality.BlueRay
            "dvd" in t -> SearchQuality.DVD
            "1080" in t || "720" in t || has("hd", "fhd", "fullhd") || "高清" in t || "超清" in t -> SearchQuality.HD
            "webrip" in t || "web-dl" in t || "webdl" in t || has("web") -> SearchQuality.WebRip
            has("hq") -> SearchQuality.HQ
            has("sd", "480p", "360p") || "标清" in t || "標清" in t -> SearchQuality.SD
            else -> null
        }
    }
}
