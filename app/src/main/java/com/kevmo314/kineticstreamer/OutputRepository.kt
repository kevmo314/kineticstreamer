package com.kevmo314.kineticstreamer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder

data class OutputConfiguration(val url: String, val enabled: Boolean) {
    val protocol: String
        get() = url.split(":", limit = 2).first().uppercase()

    /** `enabled:<url-encoded url>`; encoding keeps `;` and `:` in the url from clashing with the separators. */
    fun encode(): String = "$enabled:${URLEncoder.encode(url, "UTF-8")}"

    companion object {
        fun decode(string: String): OutputConfiguration? = parse(string, decodeUrl = true)

        /** The pre-escaping format: `enabled:url`, with the url stored verbatim. */
        fun decodeLegacy(string: String): OutputConfiguration? = parse(string, decodeUrl = false)

        private fun parse(string: String, decodeUrl: Boolean): OutputConfiguration? {
            val parts = string.split(":", limit = 2)
            if (parts.size != 2 || parts[1].isEmpty()) return null
            val enabled = when (parts[0]) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val url = if (decodeUrl) {
                try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (e: IllegalArgumentException) {
                    return null
                }
            } else {
                parts[1]
            }
            return OutputConfiguration(url, enabled)
        }
    }
}

fun encodeOutputs(outputs: List<OutputConfiguration>): String =
    outputs.joinToString(";") { it.encode() }

/** Malformed entries are skipped rather than failing the whole list. */
fun decodeOutputs(string: String?, legacy: Boolean = false): List<OutputConfiguration> =
    string?.split(";")?.mapNotNull {
        if (legacy) OutputConfiguration.decodeLegacy(it) else OutputConfiguration.decode(it)
    } ?: emptyList()

/**
 * Stores the output list in a single preference. Every mutation is one atomic `edit {}`.
 * Data written by older versions (`output_configurations`) is read until the first write migrates it.
 */
class OutputRepository(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("output_configurations_v2")
    private val legacyKey = stringPreferencesKey("output_configurations")

    val configurations: Flow<List<OutputConfiguration>> = dataStore.data.map { read(it) }

    suspend fun add(output: OutputConfiguration) = modify { it + output }

    /** No-op if [index] is out of range. */
    suspend fun update(index: Int, transform: (OutputConfiguration) -> OutputConfiguration) = modify {
        if (index in it.indices) it.toMutableList().also { l -> l[index] = transform(l[index]) } else it
    }

    /** No-op if [index] is out of range. */
    suspend fun remove(index: Int) = modify {
        if (index in it.indices) it.toMutableList().also { l -> l.removeAt(index) } else it
    }

    private fun read(prefs: Preferences): List<OutputConfiguration> =
        prefs[key]?.let { decodeOutputs(it) } ?: decodeOutputs(prefs[legacyKey], legacy = true)

    private suspend fun modify(transform: (List<OutputConfiguration>) -> List<OutputConfiguration>) {
        dataStore.edit {
            it[key] = encodeOutputs(transform(read(it)))
            it.remove(legacyKey)
        }
    }
}
