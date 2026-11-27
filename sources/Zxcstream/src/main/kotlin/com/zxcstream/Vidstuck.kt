package com.zxcstream

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * vidstuck.cc, zxcstream's "Main Server" player. Two calls per server:
 *  1. `POST /backend/vetena` with the title's id / type / server under obfuscated field names, plus
 *     `dog`: the same fields as JSON, AES-encrypted. Answers `{ts, token}`.
 *  2. `GET /backend/servers/{server}?...` with ts + token + title metadata (obfuscated names again).
 *     Every returned `link` is AES-encrypted, sometimes wrapped as `<prefix>_` + base64.
 * Both ciphers are CryptoJS passphrase mode (OpenSSL "Salted__" + EVP_BytesToKey/MD5, AES-256-CBC)
 * with one static passphrase from the player bundle.
 *
 * The passphrase and the field names are build constants, so they can change on any redeploy. We
 * read them from the live player bundle (see [VidstuckConfig.extract]), keep them for a few hours and
 * fall back to the last known values when the bundle can't be read.
 */
internal class Vidstuck(private val mapper: ObjectMapper, private val userAgent: String) {

    /** Title metadata the servers endpoint wants (the player sends the same). */
    class Meta(
        val type: String,
        val id: String,
        val season: Int?,
        val episode: Int?,
        val title: String,
        val year: String,
        val date: String,
        val latestDate: String?,
        val imdbId: String?,
    )

    private val headers
        get() = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "Origin" to BASE,
            "Referer" to "$BASE/",
        )

    /** Runs [server] for one title; null when the server has nothing. Links come back decrypted. */
    suspend fun server(meta: Meta, server: String, dubCode: String? = null, dubType: String? = null): JsonNode? {
        val cfg = config()
        val result = runCatching { call(cfg, meta, server, "/backend/servers/$server", dubCode, dubType) }
        val error = result.exceptionOrNull()
        // The bundle values were read but vidstuck refused them (400-403): try the built-in ones once.
        if (error !is Refused || !cfg.fromBundle) return result.getOrThrow()
        Log.i(TAG, "vidstuck $server with bundle config failed ($error), trying built-in values")
        return call(VidstuckConfig.FALLBACK, meta, server, "/backend/servers/$server", dubCode, dubType)
    }

    /** vidstuck's own subtitle list (MovieBox SRTs): [(label, url)]. */
    suspend fun subtitles(meta: Meta): List<Pair<String, String>> {
        val json = call(config(), meta, "subtitle", "/backend/subtitle", null, null) ?: return emptyList()
        return json["captions"]?.mapNotNull { c ->
            val file = c.text("file")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            (c.text("display") ?: "Unknown") to file
        }.orEmpty()
    }

    private suspend fun call(
        cfg: VidstuckConfig,
        meta: Meta,
        path: String,
        endpoint: String,
        dubCode: String?,
        dubType: String?,
    ): JsonNode? {
        val p = cfg.params
        val tv = meta.type == "tv"
        // 1. token
        val dog = LinkedHashMap<String, Any>()
        dog["id"] = meta.id
        dog["media_type"] = meta.type
        dog["path"] = path
        if (tv) {
            dog["season"] = meta.season ?: 1
            dog["episode"] = meta.episode ?: 1
        }
        val body = LinkedHashMap<String, Any>()
        body[p.getValue("tmdbId")] = meta.id
        body[p.getValue("media_type")] = meta.type
        body[p.getValue("path")] = path
        body["dog"] = VidstuckCrypto.encrypt(mapper.writeValueAsString(dog), cfg.encKey)
        if (tv) {
            body[p.getValue("season")] = meta.season ?: 1
            body[p.getValue("episode")] = meta.episode ?: 1
        }
        val bodyJson = mapper.writeValueAsString(body)
        val tokenResponse = limited("vetena") {
            app.post(
                "$BASE/backend/vetena",
                headers = headers,
                requestBody = bodyJson.toRequestBody("application/json".toMediaType()),
                timeout = 15L,
            )
        }
        if (tokenResponse.code in 400..403) throw Refused("vetena ${tokenResponse.code}")
        if (!tokenResponse.isSuccessful) throw IllegalStateException("vetena ${tokenResponse.code}")
        val token = mapper.readTree(tokenResponse.text)
        val ts = token.text("ts") ?: throw IllegalStateException("vetena: no ts")
        val tok = token.text("token") ?: throw IllegalStateException("vetena: no token")

        // 2. sources
        val q = LinkedHashMap<String, String>()
        q[p.getValue("tmdbId")] = meta.id
        q[p.getValue("path")] = path
        q[p.getValue("media_type")] = meta.type
        q[p.getValue("ts")] = ts
        q[p.getValue("token")] = tok
        q[p.getValue("title")] = meta.title
        q[p.getValue("year")] = meta.year
        q[p.getValue("date")] = meta.date
        if (tv) {
            q[p.getValue("season")] = (meta.season ?: 1).toString()
            q[p.getValue("episode")] = (meta.episode ?: 1).toString()
            meta.latestDate?.let { q[p.getValue("latestDate")] = it }
        }
        if (dubCode != null && dubType != null) {
            q[p["dubCode"] ?: "dubCode"] = dubCode
            q[p["dubType"] ?: "dubType"] = dubType
        }
        meta.imdbId?.let { q[p.getValue("imdbId")] = it }
        val query = q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val response = limited(path) { app.get("$BASE$endpoint?$query", headers = headers, timeout = 20L) }
        if (response.code == 404) return null
        if (response.code in 400..403) throw Refused("$path ${response.code}")
        if (!response.isSuccessful) throw IllegalStateException("$path ${response.code}")
        val json = mapper.readTree(response.text)
        val links = json["links"] ?: return json
        links.forEach { link ->
            val raw = link.text("link") ?: return@forEach
            val plain = VidstuckCrypto.decryptLink(raw, cfg.decKey)
                ?: VidstuckCrypto.decryptLink(raw, VidstuckConfig.FALLBACK.decKey).also {
                    if (it != null) Log.i(TAG, "vidstuck: bundle key failed on a link, built-in key worked")
                }
            if (plain == null) Log.i(TAG, "vidstuck $path: could not decrypt a link (key rotated?)")
            (link as? ObjectNode)?.put("link", plain ?: "")
        }
        return json
    }

    /** vidstuck refused our parameters (as opposed to rate limits or network trouble). */
    private class Refused(message: String) : Exception(message)

    /**
     * Every vidstuck API call (vetena, servers, subtitle) goes through here: at most [API_LIMIT] per
     * [API_WINDOW] across all titles. Past about 20 calls in a few seconds vidstuck answers 429 to every
     * `/backend` request for 10 s, which would also stall the Berkas playlists the player is loading,
     * so we stay well below that. A 429 anyway (another player on this IP?) is waited out once.
     */
    private suspend fun limited(label: String, request: suspend () -> com.lagradost.nicehttp.NiceResponse): com.lagradost.nicehttp.NiceResponse {
        slot()
        val first = request()
        if (first.code != 429) return first
        val wait = (first.headers["Retry-After"]?.toLongOrNull() ?: 10L).coerceIn(1L, 10L)
        Log.i(TAG, "vidstuck $label 429, waiting ${wait}s")
        delay(wait * 1000)
        slot()
        return request()
    }

    private suspend fun slot() {
        while (true) {
            val wait = synchronized(apiTimes) {
                val now = System.currentTimeMillis()
                while (apiTimes.isNotEmpty() && now - apiTimes.first() >= API_WINDOW) apiTimes.removeFirst()
                if (apiTimes.size < API_LIMIT) {
                    apiTimes.addLast(now)
                    0L
                } else {
                    API_WINDOW - (now - apiTimes.first()) + 50
                }
            }
            if (wait <= 0) return
            delay(wait)
        }
    }

    // ---- key + field names from the live player bundle ----

    private suspend fun config(): VidstuckConfig = configLock.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAt < if (it.fromBundle) CONFIG_TTL else RETRY_TTL }?.let { return@withLock it }
        val fresh = runCatching { readBundle() }
            .onFailure { Log.i(TAG, "vidstuck: reading the player bundle failed ($it); using built-in key and field names") }
            .getOrNull()
        val cfg = fresh ?: VidstuckConfig.FALLBACK
        if (fresh != null) {
            val same = fresh.encKey == VidstuckConfig.FALLBACK.encKey && fresh.params == VidstuckConfig.FALLBACK.params
            Log.i(TAG, "vidstuck: config from bundle ${lastChunk ?: "?"} (${if (same) "unchanged" else "CHANGED from built-in"})")
        }
        cached = cfg
        cachedAt = now
        cfg
    }

    private suspend fun readBundle(): VidstuckConfig {
        val page = app.get("$BASE/embed/movie/550", headers = mapOf("User-Agent" to userAgent), timeout = 15L).text
        val chunks = CHUNK_REGEX.findAll(page).map { it.value }.distinct().toList()
        if (chunks.isEmpty()) throw IllegalStateException("no script chunks on the embed page")
        // The chunk that held the config last time goes first; it usually keeps its name between deploys.
        lastChunk?.takeIf { it in chunks }?.let { name -> fetchChunk(name)?.let { VidstuckConfig.extract(it) }?.let { return it } }
        val found = chunks.amap { name ->
            val js = fetchChunk(name) ?: return@amap null
            if (!js.contains("/backend/vetena")) return@amap null
            VidstuckConfig.extract(js)?.let { name to it }
        }.firstOrNull { it != null } ?: throw IllegalStateException("no chunk with the vetena call (${chunks.size} chunks)")
        lastChunk = found.first
        return found.second
    }

    private suspend fun fetchChunk(name: String): String? = runCatching {
        app.get("$BASE$name", headers = mapOf("User-Agent" to userAgent, "Referer" to "$BASE/"), timeout = 20L)
            .takeIf { it.isSuccessful }?.text
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "Zxcstream"
        const val BASE = "https://vidstuck.cc"
        private const val CONFIG_TTL = 4 * 60 * 60 * 1000L
        private const val RETRY_TTL = 20 * 60 * 1000L
        private val CHUNK_REGEX = Regex("""/_next/static/chunks/[\w.-]+\.js""")
        private const val API_LIMIT = 12
        private const val API_WINDOW = 10_000L
        private val apiTimes = ArrayDeque<Long>()
        private val configLock = Mutex()
        @Volatile private var cached: VidstuckConfig? = null
        @Volatile private var cachedAt = 0L
        @Volatile private var lastChunk: String? = null
    }
}

/** Passphrase + obfuscated field names of the vidstuck player. */
internal class VidstuckConfig(
    val encKey: String,
    val decKey: String,
    val params: Map<String, String>,
    val fromBundle: Boolean,
) {
    companion object {
        private const val TAG = "Zxcstream"

        /** Values from the bundle as of Nov 2026. */
        val FALLBACK = VidstuckConfig(
            encKey = "7f4c9e2a81d63b05c4f7a9e8126d3b50e1a8c7f23d9465ab0c6e9f1d4a7b832c",
            decKey = "7f4c9e2a81d63b05c4f7a9e8126d3b50e1a8c7f23d9465ab0c6e9f1d4a7b832c",
            params = mapOf(
                "tmdbId" to "a7f39c821d604e5b9157b",
                "media_type" to "c1306d28147a35632e816b",
                "path" to "6b491e28392e7561a9384c",
                "season" to "d8427b5c82f957c3613e85b",
                "episode" to "91c6ea5d1f785c92346b713d",
                "ts" to "61d9a5274c8e3b2f8291e6",
                "token" to "c492f7a183d0e36c538a716d",
                "title" to "5e28c9147a0de29f3674b392a1",
                "year" to "b731e6c94f87f8341c306e",
                "date" to "e1649139e5814b3027",
                "latestDate" to "e95416ad739e5814b3027",
                "imdbId" to "f35a814265e871c4933a725f",
                "dubCode" to "dubCode",
                "dubType" to "dubType",
            ),
            fromBundle = false,
        )

        private val REQUIRED = listOf(
            "tmdbId", "media_type", "path", "season", "episode", "ts", "token", "title", "year", "date", "latestDate", "imdbId",
        )
        private val ENC_REGEX = Regex("""AES\.encrypt\(JSON\.stringify\([\w$]+\),\s*(?:"([^"]+)"|([\w$]+))\)""")
        private val DEC_REGEX = Regex("""AES\.decrypt\([\w$]+,\s*(?:"([^"]+)"|([\w$]+))\)""")
        // The player's source query: `let{media_type:t,tmdbId:r,...}=e;return{queryKey:["get-source",...`
        private val DESTRUCTURE_REGEX = Regex("""let\{([^{}]*)\}=[\w$]+;return\{queryKey:\["get-source"""")
        private val PAIR_REGEX = Regex("""([\w$]+):([\w$]+)""")
        private val COMPUTED_REGEX = Regex("""\[([\w$]+)\]:([\w$.()]+)""")
        private val SET_VAR_REGEX = Regex("""\.set\(([\w$]+),(?:String\()?([\w$]+)\)?\)""")
        private val SET_LITERAL_REGEX = Regex("""\.set\("([^"]+)",([\w$]+)\)""")

        /**
         * Reads the passphrase and field names from the player chunk:
         *  - passphrase: the literal (or constant) in `AES.encrypt(JSON.stringify(x),"...")` / `AES.decrypt(x,k)`.
         *  - field names: the source query destructures its options (`{tmdbId:r,title:u,...}=e`), then
         *    builds the vetena body and the URLSearchParams as `{[lH]:r,[lV]:u,...}`, `.set(lZ,f)` and
         *    `.set("<hex>",o)`. Matching the value variables back to the option names gives each field's
         *    constant variable; its value is the nearest earlier `lH="<hex>"` declaration.
         * Returns null (with a log line) when anything is missing, so the caller keeps the built-in values.
         */
        fun extract(js: String): VidstuckConfig? {
            val encMatch = ENC_REGEX.find(js) ?: return null.also { Log.i(TAG, "vidstuck bundle: AES.encrypt not found") }
            val encKey = encMatch.groupValues[1].ifEmpty { literal(js, encMatch.groupValues[2], encMatch.range.first) }
                ?: return null.also { Log.i(TAG, "vidstuck bundle: passphrase constant not found") }
            val decKey = DEC_REGEX.find(js)?.let { m -> m.groupValues[1].ifEmpty { literal(js, m.groupValues[2], m.range.first) } } ?: encKey

            val destructure = DESTRUCTURE_REGEX.find(js) ?: return null.also { Log.i(TAG, "vidstuck bundle: source query not found") }
            val fieldOf = PAIR_REGEX.findAll(destructure.groupValues[1]).associate { it.groupValues[2] to it.groupValues[1] }
            val start = js.indexOf("/backend/vetena", destructure.range.last)
            val end = js.indexOf("/backend/servers/", start)
            if (start < 0 || end < 0 || end - start > 4000) return null.also { Log.i(TAG, "vidstuck bundle: vetena/servers calls not found") }
            val window = js.substring(start, end)

            val varOf = LinkedHashMap<String, String>() // field -> constant variable (or "\"literal")
            COMPUTED_REGEX.findAll(window).forEach { m ->
                val k = m.groupValues[1]
                val v = m.groupValues[2]
                when {
                    v.startsWith("String(") && v.endsWith(".ts)") -> varOf.putIfAbsent("ts", k)
                    v.endsWith(".token") -> varOf.putIfAbsent("token", k)
                    v in fieldOf -> varOf.putIfAbsent(fieldOf.getValue(v), k)
                }
            }
            SET_VAR_REGEX.findAll(window).forEach { m -> fieldOf[m.groupValues[2]]?.let { varOf.putIfAbsent(it, m.groupValues[1]) } }
            SET_LITERAL_REGEX.findAll(window).forEach { m -> fieldOf[m.groupValues[2]]?.let { varOf.putIfAbsent(it, "\"" + m.groupValues[1]) } }

            val params = LinkedHashMap<String, String>()
            varOf.forEach { (field, v) ->
                val value = if (v.startsWith("\"")) v.substring(1) else literal(js, v, start)
                if (value != null) params[field] = value
            }
            val missing = REQUIRED.filter { it !in params }
            if (missing.isNotEmpty()) {
                Log.i(TAG, "vidstuck bundle: field names missing $missing")
                return null
            }
            return VidstuckConfig(encKey, decKey, params, fromBundle = true)
        }

        /** Value of the last `name="..."` declaration before [before] (minified names repeat across modules). */
        private fun literal(js: String, name: String, before: Int): String? {
            val re = Regex("""(?<![\w$])${Regex.escape(name)}="([^"]+)"""")
            return re.findAll(js.substring(0, before.coerceIn(0, js.length))).lastOrNull()?.groupValues?.get(1)
        }
    }
}

/** CryptoJS `AES.encrypt(text, passphrase)` / `AES.decrypt` compatible (OpenSSL Salted__, EVP_BytesToKey MD5). */
internal object VidstuckCrypto {
    private val random = SecureRandom()

    fun encrypt(plain: String, passphrase: String): String {
        val salt = ByteArray(8).also { random.nextBytes(it) }
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString("Salted__".toByteArray(Charsets.ISO_8859_1) + salt + ct)
    }

    fun decrypt(b64: String, passphrase: String): String? = runCatching {
        val data = java.util.Base64.getDecoder().decode(b64.trim())
        if (data.size < 32 || String(data, 0, 8, Charsets.ISO_8859_1) != "Salted__") return null
        val salt = data.copyOfRange(8, 16)
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(data, 16, data.size - 16), Charsets.UTF_8)
    }.getOrNull()

    /** A link is either the CryptoJS base64 itself or `<prefix>_` + base64(that base64). */
    fun decryptLink(raw: String, passphrase: String): String? {
        if (raw.startsWith("U2FsdGVk")) return decrypt(raw, passphrase)?.takeIf { it.isNotBlank() }
        val prefixed = Regex("""^[A-Za-z0-9]{1,12}_(.+)$""").find(raw)?.groupValues?.get(1)
        if (prefixed != null) {
            val inner = runCatching { String(java.util.Base64.getDecoder().decode(prefixed), Charsets.UTF_8) }.getOrNull()
            if (inner != null) decrypt(inner, passphrase)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // Already plain (some servers return clear links).
        return raw.takeIf { it.startsWith("http") || it.startsWith("/") }
    }

    private fun evpBytesToKey(pass: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val out = java.io.ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < 48) {
            md5.reset()
            md5.update(prev)
            md5.update(pass)
            md5.update(salt)
            prev = md5.digest()
            out.write(prev)
        }
        val bytes = out.toByteArray()
        return bytes.copyOfRange(0, 32) to bytes.copyOfRange(32, 48)
    }
}

internal fun JsonNode.text(field: String): String? =
    this[field]?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
