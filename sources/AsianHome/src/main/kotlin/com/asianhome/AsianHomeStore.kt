package com.asianhome

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData

/** One home-screen row picked from another source: that source's name and its own MainPageData. */
data class PickedRow(
    val provider: String,
    val name: String,
    val data: String,
    val horizontal: Boolean,
) {
    val key: String get() = "$provider\u0000$name"
}

/**
 * A default row. Matched by provider name plus row name; if the row was renamed, [index] into the
 * provider's mainPage is used instead. (Defaults are empty by design; users pick rows in the settings.)
 *
 */
data class DefaultRow(val provider: String, val row: String, val index: Int)

// Starts empty on purpose: each user picks their own rows in the settings (loading many sources
// at once, especially Live TV's big channel list, makes a fixed default slow).
val DEFAULT_ROWS: List<DefaultRow> = emptyList()

/** Every other installed source with a home page, and that source's rows. */
object Sources {
    fun installed(selfName: String): List<MainAPI> = try {
        APIHolder.allProviders.toList()
            .filter { it.name != selfName && it.hasMainPage }
            .distinctBy { it.name }
    } catch (_: Throwable) {
        emptyList()
    }

    fun find(name: String, selfName: String): MainAPI? =
        if (name == selfName) null else installed(selfName).firstOrNull { it.name == name }

    fun rows(api: MainAPI): List<MainPageData> = try {
        api.mainPage
    } catch (_: Throwable) {
        emptyList()
    }
}

/** Saves the user's row list in the app's SharedPreferences as a JSON array. */
class AsianHomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("asianhome", Context.MODE_PRIVATE)
    private val mapper = ObjectMapper()

    @Volatile
    private var cache: List<PickedRow>? = null

    @Volatile
    private var cacheLoaded = false

    /** The saved list, or null when the user has never saved (or reset to defaults). */
    fun saved(): List<PickedRow>? {
        if (cacheLoaded) return cache
        val parsed = try {
            prefs.getString(KEY, null)?.let { json ->
                mapper.readTree(json).mapNotNull { node ->
                    val provider = node.path("provider").asText("")
                    val name = node.path("name").asText("")
                    if (provider.isEmpty() || name.isEmpty()) null
                    else PickedRow(provider, name, node.path("data").asText(""), node.path("horizontal").asBoolean(false))
                }
            }
        } catch (_: Throwable) {
            null
        }
        cache = parsed
        cacheLoaded = true
        return parsed
    }

    fun save(rows: List<PickedRow>) {
        val array = mapper.createArrayNode()
        rows.distinctBy { it.key }.forEach { row ->
            array.addObject()
                .put("provider", row.provider)
                .put("name", row.name)
                .put("data", row.data)
                .put("horizontal", row.horizontal)
        }
        prefs.edit().putString(KEY, mapper.writeValueAsString(array)).apply()
        cache = rows.distinctBy { it.key }
        cacheLoaded = true
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        cache = null
        cacheLoaded = true
    }

    /** The rows to show: the saved list, or the defaults that are installed. */
    fun current(selfName: String): List<PickedRow> = saved() ?: defaults(selfName)

    fun defaults(selfName: String): List<PickedRow> {
        val sources = Sources.installed(selfName).associateBy { it.name }
        return DEFAULT_ROWS.mapNotNull { default ->
            val api = sources[default.provider] ?: return@mapNotNull null
            val rows = Sources.rows(api)
            val row = rows.firstOrNull { it.name == default.row } ?: rows.getOrNull(default.index)
            row?.let { PickedRow(api.name, it.name, it.data, it.horizontalImages) }
        }.distinctBy { it.key }
    }

    companion object {
        private const val KEY = "selected_rows"
    }
}
