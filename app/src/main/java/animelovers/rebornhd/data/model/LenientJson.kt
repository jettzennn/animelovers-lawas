package animelovers.rebornhd.data.model

import com.google.gson.JsonElement

/**
 * Parsing JSON toleran untuk respons Sanka (pelajaran v1: tipe field bisa
 * berubah di tengah hari — `mal_id` string vs number — dan model lama
 * yang kaku langsung gagal parse).
 *
 * Fakta live (diuji 2026-11-17 dari VM):
 * - `rating` = NUMBER di home/search (8.19) tapi STRING di detail ("8.19"),
 *   dan bisa null (beberapa film).
 * - `year` = number (2026); `views` = number atau 0; `episode` di item
 *   episode = string "710", tapi `number` di episode_list = number 311.
 * - `num` di daftar episode detail = string "1" ("01" juga muncul di judul).
 * - `id` hampir selalu number, tapi jangan percaya buta.
 *
 * Aturan: semua field angka/string yang rawan dibaca sebagai JsonElement
 * lalu dinormalisasi di sini. Gagal parse = nilai default yang aman
 * (0 / "" / null) — aplikasi menampilkan apa adanya, TIDAK crash.
 */
internal fun JsonElement?.asLenientInt(default: Int = 0): Int {
    if (this == null || isJsonNull) return default
    return try {
        when {
            isJsonPrimitive -> {
                val p = asJsonPrimitive
                when {
                    p.isNumber -> p.asInt
                    p.isString -> p.asString.trim().toIntOrNull() ?: default
                    else -> default
                }
            }
            else -> default
        }
    } catch (e: Exception) {
        default
    }
}

internal fun JsonElement?.asLenientDouble(): Double? {
    if (this == null || isJsonNull) return null
    return try {
        when {
            isJsonPrimitive -> {
                val p = asJsonPrimitive
                when {
                    p.isNumber -> p.asDouble
                    p.isString -> p.asString.trim().toDoubleOrNull()
                    else -> null
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

internal fun JsonElement?.asLenientString(): String {
    if (this == null || isJsonNull) return ""
    return try {
        when {
            isJsonPrimitive -> {
                val p = asJsonPrimitive
                when {
                    p.isString -> p.asString
                    p.isNumber -> p.asNumber.toString()
                    p.isBoolean -> p.asBoolean.toString()
                    else -> ""
                }
            }
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}

/**
 * Teks rating untuk tampilan: number 8.19 → "8.19", number 8.0 → "8",
 * string "8.19" → "8.19", null → "" (badge disembunyikan).
 */
internal fun JsonElement?.asLenientRatingText(): String {
    val d = asLenientDouble() ?: return ""
    return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
