package com.kisskh

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything secret about kisskh.co in one place. The site rotates these from time to time (new
 * common.js / appVer); when episodes or subtitles start answering 4xx, refresh the values below.
 *
 * kkey (the `kkey=` query parameter of the Episode and Sub APIs) is made by the site's obfuscated
 * common.js: a '|'-joined list of fields, prefixed by a 31-multiplier string hash of the same list,
 * PKCS#7-padded and encrypted with AES-128-CBC (key/IV embedded in the script, the key as an
 * already expanded key schedule whose first four words are the raw key), output as upper-case hex.
 */
object KisskhCrypto {
    /** Site build version that goes into kkey. */
    const val APP_VERSION = "2.8.10"
    /** Per-endpoint GUIDs that go into kkey. */
    const val VIDEO_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"
    const val SUB_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"
    private const val PLATFORM_VERSION = 4830201
    private const val SALT = "mg3c3b04ba"
    /** The six browser fields (URL, user agent, referrer, platform, appName, appCodeName); the site
     *  accepts any value here, the app it ships uses "kisskh". */
    private const val BROWSER_FIELD = "kisskh"

    private val KKEY_KEY = words(0x4f6bdaa3, -0x61d07350, 0x7f5e722d, -0x61210cec)
    private val KKEY_IV = words(0x1504af3, 0x56e619cf, 0x2e42bba6, -0x73c08f07)

    /** Subtitle cue-text keys as (key, iv), tried in order (the last one that worked first).
     *  `.txt1` files use the first one, `.txt` the second, `.txt2` the third. */
    private val SUBTITLE_KEYS = listOf(
        "AmSmZVcH93UQUezi" to "ReBKWW8cqdjPEnF6",
        "8056483646328763" to "6852612370185273",
        "sWODXX04QRTkHdlZ" to "8pwhapJeC4hrS9hO",
    )

    fun videoKey(episodeId: Long): String = kkey(episodeId, VIDEO_GUID)

    fun subtitleKey(episodeId: Long): String = kkey(episodeId, SUB_GUID)

    fun kkey(episodeId: Long, guid: String): String {
        val browser = BROWSER_FIELD.take(48)
        val fields = mutableListOf(
            "", episodeId.toString(), "", SALT, APP_VERSION, guid, PLATFORM_VERSION.toString(),
            browser, browser.lowercase().take(48), browser.take(48), browser, browser, browser, "00", "",
        )
        fields.add(1, jsHash(fields.joinToString("|")).toString())
        val plain = pkcs7(fields.joinToString("|"))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KKEY_KEY, "AES"), IvParameterSpec(KKEY_IV))
        return cipher.doFinal(plain).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    /**
     * `h = (h << 5) - h + charCode` evaluated with JavaScript numbers: the shift truncates h to
     * int32, the subtraction does not, so the result is not Java's String.hashCode.
     */
    private fun jsHash(text: String): Long {
        var h = 0L
        for (c in text) h = ((h.toInt() shl 5).toLong()) - h + c.code
        return h
    }

    /** Pads like the script: chars with the pad length, then every char's low byte. */
    private fun pkcs7(text: String): ByteArray {
        val pad = 16 - text.length % 16
        val padded = text + pad.toChar().toString().repeat(pad)
        return ByteArray(padded.length) { (padded[it].code and 0xff).toByte() }
    }

    private fun words(vararg w: Int): ByteArray = ByteArray(w.size * 4) { i ->
        (w[i / 4] ushr (24 - (i % 4) * 8) and 0xff).toByte()
    }

    /** True for subtitle files whose cue text is encrypted (`.txt`, `.txt1`, `.txt2`, ...). */
    fun isEncryptedSubtitle(url: String): Boolean =
        encryptedPath.containsMatchIn(url.substringBefore('?').substringBefore('#'))

    private val encryptedPath = Regex("""\.txt\d*$""", RegexOption.IGNORE_CASE)

    /**
     * Turns an encrypted kisskh subtitle (SRT layout, each cue text line base64 AES-128-CBC) into a
     * plain SRT. Index and timing lines are kept; a text line that doesn't decrypt is kept as is.
     */
    fun decryptSubtitle(text: String): String {
        var preferred: Pair<String, String>? = null
        return text.replace("\r\n", "\n").split('\n').joinToString("\n") { line ->
            val trimmed = line.trim()
            if (!looksEncrypted(trimmed)) return@joinToString line
            val order = listOfNotNull(preferred) + SUBTITLE_KEYS.filter { it != preferred }
            for (keys in order) {
                val plain = decryptLine(trimmed, keys) ?: continue
                preferred = keys
                return@joinToString plain
            }
            line
        }
    }

    private val base64Line = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private fun looksEncrypted(line: String): Boolean =
        line.length >= 16 && line.length % 4 == 0 && base64Line.matches(line) && !line.contains("-->")

    private fun decryptLine(line: String, keys: Pair<String, String>): String? = try {
        val data = Base64.getDecoder().decode(line)
        if (data.isEmpty() || data.size % 16 != 0) null else {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keys.first.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(keys.second.toByteArray(Charsets.UTF_8)),
            )
            // Multi-line cues are encrypted as one line with CRLF inside: keep them as SRT lines.
            val out = String(cipher.doFinal(data), Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
            // A wrong key usually fails the padding check; if it doesn't, it gives control chars.
            out.takeIf { s -> s.none { (it.code < 0x20 && it != '\n' && it != '\t') || it == '\uFFFD' } }
        }
    } catch (_: Exception) {
        null
    }
}
