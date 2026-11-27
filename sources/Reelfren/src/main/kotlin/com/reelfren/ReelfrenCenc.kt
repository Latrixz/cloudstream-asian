package com.reelfren

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Melolo's MP4s are plain (non-fragmented) MP4 files encrypted with Common Encryption, scheme
 * "cenc" (AES-128-CTR), and ReelFren hands out the content key ("meloloKey", hex). CloudStream only
 * applies ClearKey to DASH streams, so a ClearKey link can't play these files. Instead the link
 * carries the key in its URL fragment (#csasian-cenc=<hex>; fragments are never sent to the server),
 * and [interceptor] downloads the whole file, decrypts every sample in place and serves the clear
 * copy, answering range requests from memory.
 *
 * Decrypting keeps every byte offset the same: the sample entries are renamed back to their original
 * format (encv -> hvc1, enca -> mp4a, from the frma box) and the encryption boxes become "free".
 */
object ReelfrenCenc {
    private const val FRAGMENT_KEY = "csasian-cenc="
    private const val MAX_CACHED = 3

    /** The link URL for an encrypted file: the real URL plus the key in its fragment. */
    fun linkUrl(url: String, keyHex: String): String = "${url.substringBefore('#')}#$FRAGMENT_KEY${keyHex.lowercase()}"

    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MAX_CACHED
    }
    private val locks = HashMap<String, Any>()

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val keyHex = request.url.fragment?.takeIf { it.startsWith(FRAGMENT_KEY) }?.removePrefix(FRAGMENT_KEY)
        val key = keyHex?.let { hexToBytes(it) }?.takeIf { it.size == 16 }
            ?: return@Interceptor chain.proceed(request)
        val cleanUrl = request.url.newBuilder().fragment(null).build()
        val cacheKey = cleanUrl.toString()
        val lock = synchronized(locks) { locks.getOrPut(cacheKey) { Any() } }
        val clear: ByteArray = synchronized(lock) {
            synchronized(cache) { cache[cacheKey] } ?: run {
                val upstream = chain.proceed(request.newBuilder().url(cleanUrl).removeHeader("Range").build())
                if (!upstream.isSuccessful) return@Interceptor upstream
                val bytes = upstream.body.bytes()
                upstream.close()
                if (!decrypt(bytes, key)) throw IOException("Melolo: could not decrypt the video")
                synchronized(cache) { cache[cacheKey] = bytes }
                bytes
            }
        }
        serve(request, clear)
    }

    private fun serve(request: Request, data: ByteArray): Response {
        val total = data.size.toLong()
        val range = request.header("Range")?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .header("Content-Type", "video/mp4").header("Accept-Ranges", "bytes")
        if (range == null) {
            return builder.code(200).message("OK").body(data.toResponseBody("video/mp4".toMediaTypeOrNull())).build()
        }
        val (a, b) = range.destructured
        var start = a.toLongOrNull()
        var end = b.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (start == null) { // "bytes=-N": the last N bytes
            start = (total - (b.toLongOrNull() ?: total)).coerceAtLeast(0)
            end = total - 1
        }
        if (start >= total || start > end) {
            return builder.code(416).message("Range Not Satisfiable").header("Content-Range", "bytes */$total")
                .body(ByteArray(0).toResponseBody(null)).build()
        }
        val part = data.copyOfRange(start.toInt(), end.toInt() + 1)
        return builder.code(206).message("Partial Content").header("Content-Range", "bytes $start-$end/$total")
            .body(part.toResponseBody("video/mp4".toMediaTypeOrNull())).build()
    }

    /** The default KID of the first encrypted track, read from the file's start (for ClearKey hosts). */
    fun defaultKid(head: ByteArray): ByteArray? {
        val i = indexOf(head, "tenc".toByteArray(Charsets.ISO_8859_1))
        if (i < 4 || i + 4 + 24 > head.size) return null
        // tenc: version/flags(4) reserved(1) pattern/reserved(1) isProtected(1) ivSize(1) KID(16)
        return head.copyOfRange(i + 4 + 8, i + 4 + 24)
    }

    // ---- MP4 parsing ----

    private class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val body get() = start + headerSize
    }

    private fun readBoxes(d: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var o = from
        while (o + 8 <= to) {
            var size = u32(d, o)
            var header = 8
            if (size == 1L) {
                if (o + 16 > to) break
                size = u64(d, o + 8); header = 16
            } else if (size == 0L) size = (to - o).toLong()
            if (size < header || o + size > to) break
            out += Box(String(d, o + 4, 4, Charsets.ISO_8859_1), o, header, (o + size).toInt())
            o += size.toInt()
        }
        return out
    }

    private fun List<Box>.find(type: String) = firstOrNull { it.type == type }

    private class Track(
        val sizes: IntArray,
        val offsets: LongArray,
        val ivs: List<ByteArray>,
        val subsamples: List<IntArray?>, // pairs of (clear, protected) byte counts, or null = all protected
    )

    /** Decrypts [d] in place. Returns false when the file isn't laid out the way we expect. */
    fun decrypt(d: ByteArray, key: ByteArray): Boolean {
        val top = readBoxes(d, 0, d.size)
        val moov = top.find("moov") ?: return false
        val traks = readBoxes(d, moov.body, moov.end).filter { it.type == "trak" }
        val tracks = ArrayList<Track>()
        for (trak in traks) {
            val mdia = readBoxes(d, trak.body, trak.end).find("mdia") ?: continue
            val minf = readBoxes(d, mdia.body, mdia.end).find("minf") ?: continue
            val stbl = readBoxes(d, minf.body, minf.end).find("stbl") ?: continue
            val stblKids = readBoxes(d, stbl.body, stbl.end)
            val stsd = stblKids.find("stsd") ?: continue
            val entry = readBoxes(d, stsd.body + 8, stsd.end).firstOrNull() ?: continue
            if (entry.type != "encv" && entry.type != "enca") continue
            // Visual sample entries have 78 bytes of fields before their child boxes, audio ones 28.
            val entryKids = readBoxes(d, entry.body + if (entry.type == "encv") 78 else 28, entry.end)
            val sinf = entryKids.find("sinf") ?: return false
            val sinfKids = readBoxes(d, sinf.body, sinf.end)
            val frma = sinfKids.find("frma") ?: return false
            val schm = sinfKids.find("schm") ?: return false
            val scheme = String(d, schm.body + 4, 4, Charsets.ISO_8859_1)
            if (scheme != "cenc") return false
            val schi = sinfKids.find("schi") ?: return false
            val tenc = readBoxes(d, schi.body, schi.end).find("tenc") ?: return false
            val ivSize = d[tenc.body + 7].toInt() and 0xff
            val constantIv: ByteArray? = if (ivSize == 0) {
                val n = d[tenc.body + 24].toInt() and 0xff
                d.copyOfRange(tenc.body + 25, tenc.body + 25 + n)
            } else null

            val sizes = sampleSizes(d, stblKids.find("stsz") ?: return false)
            val offsets = sampleOffsets(d, stblKids, sizes) ?: return false
            val senc = stblKids.find("senc") ?: readBoxes(d, trak.body, trak.end).find("senc")
            val aux = when {
                senc != null -> parseSenc(d, senc.body, sizes.size, ivSize)
                else -> parseSaio(d, stblKids, sizes.size, ivSize)
            } ?: if (constantIv != null) List(sizes.size) { constantIv to null } else return false
            if (aux.size < sizes.size) return false
            tracks += Track(sizes, offsets, aux.map { it.first }, aux.map { it.second })

            // Rename the sample entry back to its original format and hide the encryption boxes.
            System.arraycopy(d, frma.body, d, entry.start + 4, 4)
            rename(d, sinf)
            stblKids.filter { it.type == "senc" || it.type == "saio" || it.type == "saiz" }.forEach { rename(d, it) }
            senc?.let { rename(d, it) }
        }
        if (tracks.isEmpty()) return false
        readBoxes(d, moov.body, moov.end).filter { it.type == "pssh" }.forEach { rename(d, it) }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val spec = SecretKeySpec(key, "AES")
        for (t in tracks) {
            for (i in t.sizes.indices) {
                val start = t.offsets[i]
                val size = t.sizes[i]
                if (start < 0 || start + size > d.size) return false
                val iv = ByteArray(16)
                System.arraycopy(t.ivs[i], 0, iv, 0, minOf(16, t.ivs[i].size))
                cipher.init(Cipher.DECRYPT_MODE, spec, IvParameterSpec(iv))
                val subs = t.subsamples[i]
                var pos = start.toInt()
                if (subs == null) {
                    cipher.update(d, pos, size, d, pos)
                } else {
                    val end = pos + size
                    var k = 0
                    while (k + 1 < subs.size) {
                        pos += subs[k]
                        val enc = minOf(subs[k + 1], end - pos)
                        if (enc > 0) cipher.update(d, pos, enc, d, pos)
                        pos += enc
                        k += 2
                    }
                }
            }
        }
        return true
    }

    private fun rename(d: ByteArray, box: Box) = "free".toByteArray(Charsets.ISO_8859_1).copyInto(d, box.start + 4)

    private fun sampleSizes(d: ByteArray, stsz: Box): IntArray {
        val fixed = u32(d, stsz.body + 4).toInt()
        val count = u32(d, stsz.body + 8).toInt()
        return IntArray(count) { if (fixed != 0) fixed else u32(d, stsz.body + 12 + it * 4).toInt() }
    }

    private fun sampleOffsets(d: ByteArray, stbl: List<Box>, sizes: IntArray): LongArray? {
        val chunks: LongArray = stbl.find("stco")?.let { b ->
            LongArray(u32(d, b.body + 4).toInt()) { u32(d, b.body + 8 + it * 4) }
        } ?: stbl.find("co64")?.let { b ->
            LongArray(u32(d, b.body + 4).toInt()) { u64(d, b.body + 8 + it * 8) }
        } ?: return null
        val stsc = stbl.find("stsc") ?: return null
        val entries = u32(d, stsc.body + 4).toInt()
        val first = IntArray(entries) { u32(d, stsc.body + 8 + it * 12).toInt() }
        val perChunk = IntArray(entries) { u32(d, stsc.body + 12 + it * 12).toInt() }
        val out = LongArray(sizes.size)
        var sample = 0
        var e = 0
        for (c in chunks.indices) {
            while (e + 1 < entries && first[e + 1] <= c + 1) e++
            var off = chunks[c]
            repeat(perChunk[e]) {
                if (sample >= sizes.size) return out
                out[sample] = off
                off += sizes[sample]
                sample++
            }
        }
        return if (sample == sizes.size) out else null
    }

    /** senc body: version/flags, sample count, then per sample an IV and (flag 2) its subsamples. */
    private fun parseSenc(d: ByteArray, body: Int, samples: Int, ivSize: Int): List<Pair<ByteArray, IntArray?>>? {
        val flags = u32(d, body).toInt() and 0xffffff
        val count = u32(d, body + 4).toInt()
        if (count < samples) return null
        var o = body + 8
        return List(count) {
            readAuxEntry(d, o, ivSize, flags and 2 != 0).also { (_, len) -> o += len }.first
        }
    }

    /** saiz / saio: per-sample auxiliary info stored elsewhere in the file. */
    private fun parseSaio(d: ByteArray, stbl: List<Box>, samples: Int, ivSize: Int): List<Pair<ByteArray, IntArray?>>? {
        val saiz = stbl.find("saiz") ?: return null
        val saio = stbl.find("saio") ?: return null
        var p = saiz.body
        val zFlags = u32(d, p).toInt() and 0xffffff; p += 4
        if (zFlags and 1 != 0) p += 8
        val defaultSize = d[p].toInt() and 0xff; p += 1
        val count = u32(d, p).toInt(); p += 4
        val sizes = IntArray(count) { if (defaultSize != 0) defaultSize else d[p + it].toInt() and 0xff }
        var q = saio.body
        val oVersion = d[q].toInt() and 0xff
        val oFlags = u32(d, q).toInt() and 0xffffff; q += 4
        if (oFlags and 1 != 0) q += 8
        val entries = u32(d, q).toInt(); q += 4
        if (entries != 1) return null // one block of aux info for the whole track
        var o = (if (oVersion == 0) u32(d, q) else u64(d, q)).toInt()
        if (count < samples) return null
        return List(count) {
            val entry = readAuxEntry(d, o, ivSize, sizes[it] > ivSize).first
            o += sizes[it]
            entry
        }
    }

    private fun readAuxEntry(d: ByteArray, at: Int, ivSize: Int, hasSubs: Boolean): Pair<Pair<ByteArray, IntArray?>, Int> {
        var o = at
        val iv = d.copyOfRange(o, o + ivSize); o += ivSize
        var subs: IntArray? = null
        if (hasSubs) {
            val n = u16(d, o); o += 2
            subs = IntArray(n * 2)
            for (k in 0 until n) {
                subs[k * 2] = u16(d, o)
                subs[k * 2 + 1] = u32(d, o + 2).toInt()
                o += 6
            }
        }
        return (iv to subs) to (o - at)
    }

    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xff) shl 8) or (d[o + 1].toInt() and 0xff)
    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xff) shl 24) or ((d[o + 1].toLong() and 0xff) shl 16) or
            ((d[o + 2].toLong() and 0xff) shl 8) or (d[o + 3].toLong() and 0xff)
    private fun u64(d: ByteArray, o: Int): Long = (u32(d, o) shl 32) or u32(d, o + 4)

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    fun hexToBytes(hex: String): ByteArray? =
        if (hex.length % 2 != 0 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) null
        else ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
