package com.rivestream

import java.util.Base64

/**
 * Port of the site's client-side `secretKey` function (_app chunk, the one returning "topSecret"
 * on error). The key is derived from the request's id, or the search query, or "rive" when the
 * request has neither. All arithmetic is 32-bit, like the JavaScript original.
 */
object RiveKey {
    private val TABLE = listOf(
        "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8", "FOm7b0", "axHS3q4KDq", "o9zuXQ", "4Aebt",
        "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M", "YUixDM9B", "JQvgEj0", "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL",
        "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf", "KNcFWhDvgT", "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG",
        "W81hjts92", "rJhAT", "NON7LKoMQ", "NMdY3nsKzI", "t4En5v", "Qq5cOQ9H", "Y9nwrp", "VX5FYVfsf", "cE5SJG", "x1vj1",
        "HegbLe", "zJ3nmt4OA", "gt7rxW57dq", "clIE9b", "jyJ9g", "B5jXjMCSx", "cOzZBZTV", "FTXGy", "Dfh1q1", "ny9jqZ2POI",
        "X2NnMn", "MBtoyD", "qz4Ilys7wB", "68lbOMye", "3YUJnmxp", "1fv5Imona", "PlfvvXD7mA", "ZarKfHCaPR", "owORnX", "dQP1YU",
        "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa", "7UjkKrp", "Yvhrj", "wYXez5Dg3", "pG4GMU", "MwMAu", "rFRD5wlM",
    )

    @JvmStatic
    fun of(input: String?): String {
        if (input == null) return "rive"
        return try {
            val numeric = input.trim().toLongOrNull()
            val salt: String
            val pos: Int
            if (numeric == null) {
                val sum = input.sumOf { it.code }
                salt = TABLE[sum % TABLE.size]
                pos = (sum % input.length) / 2
            } else {
                salt = TABLE[(numeric % TABLE.size).toInt()]
                pos = ((numeric % input.length) / 2).toInt()
            }
            val mixed = input.substring(0, pos) + salt + input.substring(pos)
            val hex = outer(inner(mixed))
            Base64.getEncoder().encodeToString(hex.toByteArray(Charsets.ISO_8859_1))
        } catch (_: Throwable) {
            "topSecret"
        }
    }

    // JavaScript's Number.toString(16) of a signed 32-bit value ("-" prefix for negatives), then padStart(8, "0").
    private fun hex(n: Int): String = java.lang.Long.toString(n.toLong(), 16).padStart(8, '0')

    private fun mul(n: Int, k: Int): Int = (n and 0xFFFF) * k + (((n ushr 16) * k) shl 16)

    private fun inner(s: String): String {
        var t = 0
        for (n in s.indices) {
            val r = s[n].code
            t = r + (t shl 6) + (t shl 16) - t
            val i = (t shl (n % 5)) or (t ushr (32 - n % 5))
            t = t xor (i xor ((r shl (n % 7)) or (r ushr (8 - n % 7))))
            t += (t ushr 11) xor (t shl 3)
        }
        t = t xor (t ushr 15)
        t = (t and 0xFFFF) * 49842 + ((((t ushr 16) * 49842) and 0xFFFF) shl 16)
        t = t xor (t ushr 13)
        t = (t and 0xFFFF) * 40503 + ((((t ushr 16) * 40503) and 0xFFFF) shl 16)
        t = t xor (t ushr 16)
        return hex(t)
    }

    private fun outer(s: String): String {
        var n = 0xDEADBEEF.toInt() xor s.length
        for (e in s.indices) {
            var r = s[e].code
            r = r xor (((131 * e + 89) xor (r shl (e % 5))) and 255)
            n = ((n shl 7) or (n ushr 25)) xor r
            n = mul(n, 60205)
            n = n xor (n ushr 11)
        }
        n = n xor (n ushr 15)
        n = mul(n, 49842)
        n = n xor (n ushr 13)
        n = mul(n, 40503)
        n = n xor (n ushr 16)
        n = mul(n, 10196)
        n = n xor (n ushr 15)
        return hex(n)
    }
}
