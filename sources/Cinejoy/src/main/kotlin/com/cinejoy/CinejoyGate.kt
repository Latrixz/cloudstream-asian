package com.cinejoy

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed envelope of `POST api2.wing.st/g` ("lumen-gate-v2"), which the site builds in
 * `api2.wing.st/crush.wasm` (Rust: p256 + hkdf + aes-gcm).
 *
 *  - A fresh P-256 key pair per request. ECDH with the gate's public key gives the shared x `z`.
 *  - HKDF-SHA256, salt = our uncompressed public key (65 bytes), ikm = z:
 *    info "lumen-gate-v2|c2s" -> request key, "lumen-gate-v2|s2c" -> response key.
 *  - Request body: `02 02` + public key (65) + nonce (12) + AES-256-GCM(plaintext),
 *    AAD = "lumen-gate-v2" `00 01 02` + public key.
 *  - Response body: iv (12) + AES-256-GCM(json), AAD = "lumen-gate-v2" `00 02 02` + public key.
 *
 * crush.wasm is served no-cache. If the gate starts refusing us, compare [SERVER_PUBLIC_KEY] and the
 * labels below with a fresh crush.wasm (the key is the only 65-byte `04...` P-256 point in its data;
 * the labels sit next to it as plain text).
 */
internal object CinejoyGate {
    /** The gate's P-256 public key, uncompressed, as embedded in crush.wasm. */
    const val SERVER_PUBLIC_KEY =
        "045c88a0ae33c683a4872590b04b22f4774a4fe1c6ecf74785c74a919a71207ca9" +
            "4f9e6b5a7854c3aa3b44ee46bc444fea694b9f23bcd80f864ecbef48c1ef6e14"
    const val LABEL = "lumen-gate-v2"
    const val INFO_REQUEST = "$LABEL|c2s"
    const val INFO_RESPONSE = "$LABEL|s2c"
    private val VERSION = byteArrayOf(0x02, 0x02)
    private val AAD_REQUEST = byteArrayOf(0x00, 0x01, 0x02)
    private val AAD_RESPONSE = byteArrayOf(0x00, 0x02, 0x02)

    private val random = SecureRandom()

    /** One request's keys, kept to open the matching response. */
    class Sealed(val body: ByteArray, private val publicKey: ByteArray, private val responseKey: ByteArray) {
        /** Decrypts the gate's answer; throws if it was not sealed for this request. */
        fun open(response: ByteArray): String {
            require(response.size > 12 + 16) { "response too short (${response.size} bytes)" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(responseKey, "AES"), GCMParameterSpec(128, response, 0, 12))
            cipher.updateAAD(LABEL.toByteArray() + AAD_RESPONSE + publicKey)
            return String(cipher.doFinal(response, 12, response.size - 12), Charsets.UTF_8)
        }
    }

    fun seal(plaintext: String): Sealed {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val ours = pair.public as ECPublicKey
        val params = ours.params
        val publicKey = encodePoint(ours.w)

        val server = hex(SERVER_PUBLIC_KEY)
        require(server.size == 65 && server[0] == 0x04.toByte()) { "bad SERVER_PUBLIC_KEY" }
        val serverPoint = ECPoint(BigInteger(1, server.copyOfRange(1, 33)), BigInteger(1, server.copyOfRange(33, 65)))
        val serverKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(serverPoint, params))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(serverKey, true)
        val shared = agreement.generateSecret()

        val prk = hmac(publicKey, shared)
        val requestKey = expand(prk, INFO_REQUEST.toByteArray())
        val responseKey = expand(prk, INFO_RESPONSE.toByteArray())

        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(requestKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(LABEL.toByteArray() + AAD_REQUEST + publicKey)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Sealed(VERSION + publicKey + nonce + sealed, publicKey, responseKey)
    }

    /** HKDF-SHA256 expand for one 32-byte block (RFC 5869: T(1) = HMAC(prk, info || 0x01)). */
    private fun expand(prk: ByteArray, info: ByteArray): ByteArray = hmac(prk, info + byteArrayOf(0x01))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun encodePoint(p: ECPoint): ByteArray = byteArrayOf(0x04) + fixed(p.affineX) + fixed(p.affineY)

    /** Big-endian, exactly 32 bytes (BigInteger may add a sign byte or drop leading zeros). */
    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
