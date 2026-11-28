package com.rivestream

import android.util.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The `secretKey` that rivestream's `/api/backendfetch` wants on every request ("rive-key-v2", in the
 * site's `_app` chunk since late Nov 2026). The page derives it from the request's id, or the search
 * query, or a fixed placeholder when the request has neither, and it changes every UTC day:
 *
 *   s     = id / query / "__rive_global__"
 *   salt  = SALTS[fnv1a32(utf8(s)) % SALTS.size]
 *   day   = floor(epochMillis / 86_400_000)
 *   k     = HMAC-SHA256(PEPPER, "rive-key-v2:" + day + ":" + salt)
 *   key   = base64url(HMAC-SHA256(k, day + ":" + s.length + ":" + s)[0 until 18]), no padding
 *
 * The backend accepts the previous and next day too. The pepper, the salt table, the placeholder and
 * the prefix are build constants, so we read them from the live `_app` chunk ([RiveKeyConfig.extract]),
 * keep them for a few hours and fall back to the values below when the bundle can't be read.
 */
internal object RiveKey {
    private const val TAG = "Rivestream"
    private const val BASE = "https://www.rivestream.app"
    private const val CONFIG_TTL = 6 * 60 * 60 * 1000L
    private const val RETRY_TTL = 20 * 60 * 1000L
    private const val REFRESH_GAP = 5 * 60 * 1000L
    private const val DAY_MS = 86_400_000L
    private val APP_CHUNK_REGEX = Regex("""/_next/static/chunks/pages/_app-[\w-]+\.js""")
    private val CHUNK_REGEX = Regex("""/_next/static/chunks/[\w./-]+\.js""")

    private val lock = Mutex()
    @Volatile private var cached: RiveKeyConfig? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var lastChunk: String? = null

    /** The key for [input] (id or query; null for requests that have neither) with the current config. */
    suspend fun of(input: String?, userAgent: String): String = derive(input, config(userAgent))

    fun derive(input: String?, cfg: RiveKeyConfig, nowMs: Long = System.currentTimeMillis()): String {
        val s = input?.takeIf { it.isNotEmpty() } ?: cfg.global
        val salt = cfg.salts[(fnv1a(s.toByteArray(Charsets.UTF_8)) % cfg.salts.size).toInt()]
        val day = Math.floorDiv(nowMs, DAY_MS)
        val k = hmac(cfg.pepper.toByteArray(Charsets.UTF_8), "${cfg.prefix}$day:$salt")
        val mac = hmac(k, "$day:${s.length}:$s").copyOf(cfg.length)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac)
    }

    /**
     * Called after the backend answered "Invalid secret key". Re-reads the bundle (at most every few
     * minutes) and returns a config to retry with, or null when there is nothing different to try.
     */
    suspend fun refreshAfterReject(userAgent: String): RiveKeyConfig? = lock.withLock {
        val current = cached
        val now = System.currentTimeMillis()
        if (current != null && now - cachedAt < REFRESH_GAP) {
            // Just read; the only other thing worth trying is the built-in set.
            return@withLock RiveKeyConfig.FALLBACK.takeIf { current.fromBundle && !current.sameKeyAs(it) }
        }
        val fresh = readOrNull(userAgent)
        val next = fresh ?: RiveKeyConfig.FALLBACK
        cached = next
        cachedAt = now
        if (current != null && next.sameKeyAs(current)) {
            Log.w(TAG, "secretKey rejected and the site bundle has the same key material; the algorithm may have changed")
            null
        } else next
    }

    private suspend fun config(userAgent: String): RiveKeyConfig = lock.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAt < if (it.fromBundle) CONFIG_TTL else RETRY_TTL }?.let { return@withLock it }
        val cfg = readOrNull(userAgent) ?: RiveKeyConfig.FALLBACK
        cached = cfg
        cachedAt = now
        cfg
    }

    private suspend fun readOrNull(userAgent: String): RiveKeyConfig? {
        val fresh = runCatching { readBundle(userAgent) }
            .onFailure { Log.w(TAG, "secretKey: reading the site bundle failed ($it); using the built-in key material") }
            .getOrNull()
        if (fresh != null) {
            val same = fresh.sameKeyAs(RiveKeyConfig.FALLBACK)
            Log.i(TAG, "secretKey: key material from ${lastChunk ?: "?"} (${if (same) "unchanged" else "CHANGED from built-in"})")
        }
        return fresh
    }

    private suspend fun readBundle(userAgent: String): RiveKeyConfig {
        val page = app.get("$BASE/", headers = mapOf("User-Agent" to userAgent), timeout = 15L).text
        val chunks = CHUNK_REGEX.findAll(page).map { it.value }.distinct().toList()
        if (chunks.isEmpty()) throw IllegalStateException("no script chunks on the home page")
        // The _app chunk holds the key code; try it (and the chunk that worked last time) first.
        val preferred = (listOfNotNull(lastChunk?.takeIf { it in chunks }) + chunks.filter { APP_CHUNK_REGEX.matches(it) }).distinct()
        for (name in preferred) {
            val js = fetchChunk(name, userAgent) ?: continue
            RiveKeyConfig.extract(js)?.let { lastChunk = name; return it }
        }
        val found = (chunks - preferred.toSet()).amap { name ->
            val js = fetchChunk(name, userAgent)?.takeIf { it.contains("rive-key-v") } ?: return@amap null
            RiveKeyConfig.extract(js)?.let { name to it }
        }.firstOrNull { it != null } ?: throw IllegalStateException("no chunk with the rive-key code (${chunks.size} chunks)")
        lastChunk = found.first
        return found.second
    }

    private suspend fun fetchChunk(name: String, userAgent: String): String? = runCatching {
        app.get("$BASE$name", headers = mapOf("User-Agent" to userAgent, "Referer" to "$BASE/"), timeout = 20L)
            .takeIf { it.isSuccessful }?.text
    }.getOrNull()

    private fun fnv1a(bytes: ByteArray): Long {
        var h = 2166136261L
        for (b in bytes) h = ((h xor (b.toLong() and 0xFF)) * 16777619L) and 0xFFFFFFFFL
        return h
    }

    private fun hmac(key: ByteArray, msg: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray(Charsets.UTF_8))
}

internal class RiveKeyConfig(
    val pepper: String,
    val prefix: String,
    val global: String,
    val salts: List<String>,
    val length: Int,
    val fromBundle: Boolean,
) {
    fun sameKeyAs(o: RiveKeyConfig) =
        pepper == o.pepper && prefix == o.prefix && global == o.global && salts == o.salts && length == o.length

    companion object {
        private const val TAG = "Rivestream"

        val FALLBACK = RiveKeyConfig(
            pepper = "rv_ppr_9f4c2e8a71b0d635_prod_v2",
            prefix = "rive-key-v2:",
            global = "__rive_global__",
            salts = listOf(
                "k9Vx2mQp7LzR4nWt", "bH8cY3jFs6KdP1qN", "wE5tG9uMx2ZaC7vB", "pJ4rN8yHk1WqL6sD", "mT7zX3bVc9FnQ2gK",
                "dR6vP1wMs8LhY4jZ", "nC9kB5fGt2QxJ7pW", "qL3sW8dNy6ZmH1vR", "gM2hJ7pKc4VbT9xF", "zF4qD6nRx1PwK8mL",
                "xN8wT2kVb5YcG3hJ", "vK1pM6zQs9RdF4nW", "jP5yL9hBc3XtN7mK", "cW7gR4vKf2MnZ8qP", "hZ3nQ8xJm6KpD1sV",
                "tB6fK1cWp9HvL4yN", "rY9dM4sGx7JqP2wC", "sQ2vN7bTz5LkW8fH", "fX4mH9kPd1ZcR6jB", "yG8pC3wVn4QtM7sK",
                "uD5jW2fLq8BxN9rT", "aV1zR6nKg3HpY5mC", "eK7tB4yMx9WsF2qL", "iN3cP8vJd6QrH1wZ", "oS6xG1mKt4ZbV7pN",
                "lH9wF5qRc2YnK8dM", "mZ4bT7pNx1VkJ6sG", "pQ8kL3dWv6CfR9hY", "nR2vY6jHs5MxB4tK", "wG5cM9zQp8LdN1xF",
                "kD7fH2nWr4PqT6vB", "bJ1xK8sVm3ZcL9yP", "tM6qW4gNd7RpH2fC", "vC9pZ1kTx5JnY8wS", "hL3rF7vMb2QdK6nG",
                "zP8yN4cJx9WtB1mR", "xS2dV6hKq3GfM7pL", "qT5mR9wBc1ZpN4kY", "gK1nJ7fPx8HvC3sW", "dW4zB2tLm6YqR9vN",
                "rF8hQ5pKd3MxT1cJ", "sN6vC1yWg9ZpL4bM", "fB3kM8xRt2QnH7wP", "yP7jD4nVs5KcF9gL", "uQ9wT6mZb1RxN3hK",
                "aM2cL5pGf8JdV4yS", "eR4xH1kWn7BtP6qZ", "iV8fK3sYq2MpC9nD", "oW5nG7vJc4LzT1rB", "lZ1pS9dKm6YxH8fQ",
                "cN7tR3bWx5QpM2vJ", "jK4mF8yHc1VnD6sL", "wB9qP2gZt7RfK3xM", "nY6hV4kNs8CdL1pW", "pD3zM7wGx2TqJ9fR",
                "kX8cB5nKp4VmH6yT", "bS1rL9vQd3WfN7jC", "tF5yK2mPx6ZnG4hV", "vH7wJ6cRb9LpQ1sM", "hQ2dN4fMt8YxK5zP",
                "zG6pW1sVc3JnR8kB", "xC9mT5hKf7QdM2yL", "qJ3vB8nZy1WpF4gS", "gR5kH7dLx9CtN6wV",
            ),
            length = 18,
            fromBundle = false,
        )

        // w(b,v("rive-key-v2:".concat(e,":").concat(u[t])))  ->  pepper variable, prefix, salt variable
        private val DERIVE_REGEX =
            Regex("""\w+\(([\w$]+),[\w$]+\("(rive-key-v\d+:)"\.concat\([\w$]+,":"\)\.concat\(([\w$]+)\[[\w$]+]\)\)\)""")
        // ...return c;let t="symbol"==typeof e  ->  placeholder variable
        private val GLOBAL_REGEX = Regex("""return ([\w$]+);let [\w$]+="symbol"==typeof""")
        private val LENGTH_REGEX = Regex("""\.subarray\(0,(\d+)\)""")
        private val ALGORITHM_MARKERS = listOf("2166136261", "16777619", "864e5", "rive-key-v")

        /** Reads the key material from the chunk that holds the rive-key code; null (logged) when it can't. */
        fun extract(js: String): RiveKeyConfig? {
            val anchor = js.indexOf("\"rive-key-v")
            if (anchor < 0) return null
            val missing = ALGORITHM_MARKERS.filter { !js.contains(it) }
            if (missing.isNotEmpty()) Log.w(TAG, "secretKey bundle: algorithm markers missing $missing; the key code may have changed")
            // Single-letter names repeat across a minified chunk: only look near the key code.
            val windowStart = maxOf(0, anchor - 40_000)
            val near = js.substring(windowStart, minOf(js.length, anchor + 3_000))
            val derive = DERIVE_REGEX.find(near)
                ?: return null.also { Log.w(TAG, "secretKey bundle: HMAC(pepper, \"rive-key-v..\") call not found") }
            val (pepperVar, prefix, saltsVar) = derive.destructured
            val before = near.substring(0, derive.range.first)
            val pepper = lastMatch(before, """(?<![\w$.])${Regex.escape(pepperVar)}=[\w$]+\("([^"]+)"\)""")
                ?: return null.also { Log.w(TAG, "secretKey bundle: pepper constant $pepperVar not found") }
            val saltsRaw = lastMatch(before, """(?<![\w$.])${Regex.escape(saltsVar)}=\[((?:"[^"]*",?)+)]""")
                ?: return null.also { Log.w(TAG, "secretKey bundle: salt table $saltsVar not found") }
            val salts = Regex(""""([^"]*)"""").findAll(saltsRaw).map { it.groupValues[1] }.toList()
            if (salts.isEmpty()) return null.also { Log.w(TAG, "secretKey bundle: salt table is empty") }
            val global = GLOBAL_REGEX.find(near)?.groupValues?.get(1)
                ?.let { v -> lastMatch(before, """(?<![\w$.])${Regex.escape(v)}="([^"]*)"""") }
                ?: FALLBACK.global.also { Log.w(TAG, "secretKey bundle: placeholder input not found, using \"$it\"") }
            val length = LENGTH_REGEX.find(near, derive.range.last)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..32 }
                ?: FALLBACK.length.also { Log.w(TAG, "secretKey bundle: key length not found, using $it") }
            return RiveKeyConfig(pepper, prefix, global, salts, length, fromBundle = true)
        }

        private fun lastMatch(text: String, pattern: String): String? =
            Regex(pattern).findAll(text).lastOrNull()?.groupValues?.get(1)
    }
}
