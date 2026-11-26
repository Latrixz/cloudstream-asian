package com.movy

import java.util.Base64

/**
 * Port of the movy.sx stream-API payload cipher (`enc=2`). The response body is base64url;
 * it is XORed with a keystream derived from (seed, mediaId) and must start with "mvm1".
 * All arithmetic mirrors JavaScript's 32-bit unsigned semantics (Int multiply == Math.imul).
 */
object MovyCipher {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1,
        0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    )
    private val MAGIC = byteArrayOf(109, 118, 109, 49) // "mvm1"
    private const val GOLDEN = 0x9e3779b9.toInt()

    private fun fmix(x: Int): Int {
        var e = x
        e = e xor (e ushr 16)
        e *= 0x85ebca6b.toInt()
        e = e xor (e ushr 13)
        e *= 0xc2b2ae35.toInt()
        return e xor (e ushr 16)
    }

    private fun rotl(e: Int, a: Int): Int = Integer.rotateLeft(e, a and 31)

    private fun umod(x: Int, m: Int): Int = ((x.toLong() and 0xFFFFFFFFL) % m).toInt()

    private fun fnv(s: String): Int {
        var a = 0x811c9dc5.toInt()
        for (c in s) a = (a xor c.code) * 0x1000193
        return fmix(a)
    }

    private fun keystream(seed: String, mediaId: Long, length: Int): ByteArray {
        // JS `e.length*(e.length+1)&1` is always 0, so only the sparse-table branch is reachable.
        val table = arrayOfNulls<Int>(61)
        var n = fmix(fnv(seed) xor fmix(mediaId.toInt() xor GOLDEN))
        for (e in 0 until 8) {
            val a = umod(n, 61)
            n = rotl(n + GOLDEN, 7 + (7 and e))
            table[a] = n xor fmix(n)
            n = fmix(n + a)
        }
        var acc = fmix(0xa5a5a5a5.toInt() xor n)

        val out = ByteArray(length)
        var counter = 0
        var pos = 0
        while (pos < length) {
            val d = umod(acc, 61)
            val present = table[d]
            val r = present ?: 0
            val c = GOLDEN * (counter + 1)
            val s = r xor c
            // (l ^ s) | (l & s & mask) where mask is -1 when the slot is set.
            var b = if (present != null) (acc xor s) or (acc and s) else acc xor s
            b = rotl(b + acc, d and 31) xor rotl(acc, (d * 7) and 31)
            acc = fmix(b + GOLDEN)
            table[d] = acc
            counter++
            for (shift in 0 until 4) {
                if (pos >= length) break
                out[pos++] = (acc ushr (8 * shift)).toByte()
            }
        }
        return out
    }

    /** Returns the decrypted UTF-8 text, or throws if the seed does not match. */
    fun decrypt(payload: String, seed: String, mediaId: Long): String {
        val clean = payload.trim().trim('"').replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = clean.padEnd((clean.length + 3) / 4 * 4, '=')
        val data = Base64.getDecoder().decode(padded)
        val ks = keystream(seed, mediaId, data.size)
        for (i in data.indices) data[i] = (data[i].toInt() xor ks[i].toInt()).toByte()
        for (i in MAGIC.indices) {
            if (i >= data.size || data[i] != MAGIC[i]) throw IllegalStateException("decrypt failed: bad seed or tampered payload")
        }
        return String(data, MAGIC.size, data.size - MAGIC.size, Charsets.UTF_8)
    }
}
