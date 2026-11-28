package com.uvod

import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Request/response crypto of the uvod.tv H5 API (api-h5.uvod.tv), as done by the site's
 * js/chunk-common.*.js. Plain JVM code (no Android/CloudStream classes) so it can be checked
 * against the site's JavaScript outside the app.
 *
 *  - Body: the JSON payload is AES-256-CBC/PKCS7 encrypted with a random 32-character key and the
 *    fixed IV "abcdefghijklmnop"; the key itself is RSA (PKCS#1 v1.5) encrypted with the server's
 *    public key. Sent as the JSON string "{base64 aes}.{base64 rsa}".
 *  - Response: same format; the AES key is RSA encrypted for the client key pair whose private
 *    key ships in the site's JavaScript.
 *  - Headers: X-TOKEN (empty for guests), X-TIMESTAMP (ms) and
 *    X-SIGNATURE = md5("{token}-{query}-{timestamp}"), where query is the payload without
 *    empty values (0, "0", "", false, null), keys sorted, qs-encoded and lowercased.
 */
object UvodApi {
    private const val IV = "abcdefghijklmnop"
    private const val KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

    private const val SERVER_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCeBQWotWOpsuPn3PAA+bcmM8YD" +
            "fEOzPz7hb/vItV43vBJV2FcM72Hdcv3DccIFuEV9LQ8vcmuetld98eksja9vQ1Ol" +
            "8rTnjpTpMbd4HedevSuIhWidJdMAOJKDE3AgGFcQvQePs80uXY2JhTLkRn2ICmDR" +
            "/fb32OwWY3QGOvLcuQIDAQAB"

    private const val CLIENT_PRIVATE_KEY =
        "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAJ4FBai1Y6my4+fc" +
            "8AD5tyYzxgN8Q7M/PuFv+8i1Xje8ElXYVwzvYd1y/cNxwgW4RX0tDy9ya562V33x" +
            "6SyNr29DU6XytOeOlOkxt3gd5169K4iFaJ0l0wA4koMTcCAYVxC9B4+zzS5djYmF" +
            "MuRGfYgKYNH99vfY7BZjdAY68ty5AgMBAAECgYB1rbvHJj5wVF7Rf4Hk2BMDCi9+" +
            "zP4F8SW88Y6KrDbcPt1QvOonIea56jb9ZCxf4hkt3W6foRBwg86oZo2FtoZcpCJ+" +
            "rFqUM2/wyV4CuzlL0+rNNSq7bga7d7UVld4hQYOCffSMifyF5rCFNH1py/4Dvswm" +
            "pi5qljf+dPLSlxXl2QJBAMzPJ/QPAwcf5K5nngQtbZCD3nqDFpRixXH4aUAIZcDz" +
            "S1RNsHrT61mEwZ/thQC2BUJTQNpGOfgh5Ecd1MnURwsCQQDFhAFfmvK7svkygoKX" +
            "t55ARNZy9nmme0StMOfdb4Q2UdJjfw8+zQNtKFOM7VhB7ijHcfFuGsE7UeXBe20n" +
            "g/XLAkEAv9SoT2hgJaQxxUk4MCF8pgddstJlq8Z3uTA7JMa4x+kZfXTm/6TOo6I8" +
            "2VbXZLsYYe8op0lvsoHMFvBSBljV0QJBAKhxyoYRa98dZB5qZRskciaXTlge0WJk" +
            "kA4vvh3/o757izRlQMgrKTfng1GVfIZFqKtnBiIDWTXQw2N9cnqXtH8CQAx+CD5t" +
            "l1iT0cMdjvlMg2two3SnpOjpo7gALgumIDHAmsUWhocLtcrnJI032VQSUkNnLq9z" +
            "EIfmHDz0TPVNHBQ="

    private val random = SecureRandom()
    private val serverKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(SERVER_PUBLIC_KEY)))
    }
    private val clientKey by lazy {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(CLIENT_PRIVATE_KEY)))
    }

    fun randomKey(length: Int = 32): String =
        (1..length).map { KEY_CHARS[random.nextInt(KEY_CHARS.length)] }.joinToString("")

    /** "{AES(json) base64}.{RSA(aesKey) base64}" — the site's `encrypt()`. */
    fun encrypt(json: String, aesKey: String = randomKey()): String {
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(IV.toByteArray()))
        val body = Base64.getEncoder().encodeToString(aes.doFinal(json.toByteArray(Charsets.UTF_8)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, serverKey)
        val key = Base64.getEncoder().encodeToString(rsa.doFinal(aesKey.toByteArray(Charsets.UTF_8)))
        return "$body.$key"
    }

    /** The site's `decrypt()`: the AES key length (16/24/32 bytes) picks AES-128/192/256. */
    fun decrypt(text: String): String {
        val parts = text.trim().split('.')
        require(parts.size == 2) { "unexpected response format" }
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.DECRYPT_MODE, clientKey)
        val aesKey = rsa.doFinal(Base64.getDecoder().decode(parts[1]))
        val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(IV.toByteArray()))
        return String(aes.doFinal(Base64.getDecoder().decode(parts[0])), Charsets.UTF_8)
    }

    /** The site's filter + ksort + qs.stringify (RFC 3986) + toLowerCase(). */
    fun query(params: Map<String, Any?>): String =
        params.entries
            .filterNot { (_, v) -> isEmptyValue(v) }
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "${qsEncode(k)}=${qsEncode(v.toString())}" }
            .lowercase()

    fun signature(token: String, query: String, timestamp: Long): String =
        MessageDigest.getInstance("MD5").digest("$token-$query-$timestamp".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Headers for a guest request (no X-TOKEN), signed for [params]. */
    fun headers(params: Map<String, Any?>, timestamp: Long = System.currentTimeMillis()): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "X-TOKEN" to "",
        "X-TIMESTAMP" to timestamp.toString(),
        "X-SIGNATURE" to signature("", query(params), timestamp),
    )

    private fun isEmptyValue(value: Any?): Boolean = when (value) {
        null -> true
        is Boolean -> !value
        is String -> value.isEmpty() || value == "0"
        is Number -> value.toDouble() == 0.0
        else -> false
    }

    /** qs keeps only A-Z a-z 0-9 - . _ ~ ; everything else is UTF-8 percent-encoded. */
    private fun qsEncode(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
}
