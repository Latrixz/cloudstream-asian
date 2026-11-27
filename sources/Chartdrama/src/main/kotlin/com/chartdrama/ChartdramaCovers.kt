package com.chartdrama

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Covers on SkyStream (desktop). res.chartdrama.com only serves images with a chartdrama.com
 * Referer; CloudStream sends our posterHeaders, SkyStream doesn't. On SkyStream the plugin runs on
 * the user's own computer, so it serves covers itself on 127.0.0.1: SkyStream asks this tiny local
 * server, which fetches the cover with the Referer and passes it on. Only res.chartdrama.com images
 * are fetched, and only for requests from the same machine.
 */
object ChartdramaCovers {
    private const val TAG = "Chartdrama"
    private const val HOST = "res.chartdrama.com"
    private const val REFERER = "https://chartdrama.com/"
    private const val MAX_BYTES = 4 * 1024 * 1024

    @Volatile private var port = 0

    /** The local address for [url], or [url] itself if the relay can't start. */
    fun local(url: String): String {
        if (!url.contains("://$HOST/")) return url
        val p = start()
        return if (p > 0) "http://127.0.0.1:$p/c?u=" + URLEncoder.encode(url, "UTF-8") else url
    }

    @Synchronized
    private fun start(): Int {
        if (port > 0) return port
        return try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            port = server.localPort
            Thread({
                while (true) {
                    val socket = try { server.accept() } catch (_: Throwable) { break }
                    Thread({ serve(socket) }, "chartdrama-cover").apply { isDaemon = true }.start()
                }
            }, "chartdrama-covers").apply { isDaemon = true }.start()
            Log.i(TAG, "cover relay on 127.0.0.1:$port")
            port
        } catch (e: Throwable) {
            Log.w(TAG, "cover relay failed to start: $e")
            -1
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val line = readLine(input) ?: return
                while (readLine(input)?.isNotEmpty() == true) { /* skip request headers */ }
                val path = line.split(' ').getOrNull(1) ?: return reply(s.getOutputStream(), 400, null, null)
                val target = path.substringAfter("u=", "").substringBefore('&')
                    .let { URLDecoder.decode(it, "UTF-8") }
                val url = runCatching { URL(target) }.getOrNull()
                if (url == null || url.protocol != "https" || url.host != HOST) {
                    return reply(s.getOutputStream(), 403, null, null)
                }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Referer", REFERER)
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val type = conn.contentType.orEmpty()
                if (conn.responseCode != 200 || !type.startsWith("image/")) {
                    return reply(s.getOutputStream(), 502, null, null)
                }
                val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
                reply(s.getOutputStream(), 200, type, bytes)
            } catch (_: Throwable) {
            }
        }
    }

    private fun reply(out: OutputStream, code: Int, type: String?, body: ByteArray?) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 400 -> "Bad Request"; else -> "Bad Gateway" }
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            if (type != null) append("Content-Type: $type\r\n")
            append("Content-Length: ${body?.size ?: 0}\r\n")
            append("Cache-Control: public, max-age=86400\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body != null) out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
