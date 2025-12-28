package com.kevmo314.kineticstreamer

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow

enum class SupportedVideoCodec(val mimeType: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    H265(MediaFormat.MIMETYPE_VIDEO_HEVC),
    VP8(MediaFormat.MIMETYPE_VIDEO_VP8),
    VP9(MediaFormat.MIMETYPE_VIDEO_VP9),
    @RequiresApi(Build.VERSION_CODES.Q)
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1);
}

enum class SupportedAudioCodec(val mimeType: String) {
    AAC(MediaFormat.MIMETYPE_AUDIO_AAC),
    OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS);
}


fun bytesToString(bytes: Long): String {
    val unit = 1024
    if (bytes < unit) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    val pre = "KMGTPE"[exp - 1] + "i"
    return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
}

data class Resolution(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x${height}"

    companion object {
        fun fromString(string: String): Resolution {
            val (width, height) = string.split("x")
            return Resolution(width.toInt(), height.toInt())
        }
    }
}

sealed class OutputConfiguration {
    abstract val enabled: Boolean
    abstract val displayName: String
    abstract val protocol: String
    abstract fun withEnabled(enabled: Boolean): OutputConfiguration
    abstract fun toJSON(): JSONObject

    data class WHIP(
        val url: String,
        val bearerToken: String,
        override val enabled: Boolean = true
    ) : OutputConfiguration() {
        override val displayName: String get() = url
        override val protocol: String get() = "WHIP"
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
        override fun toJSON(): JSONObject = JSONObject().apply {
            put("type", "WHIP")
            put("enabled", enabled)
            put("url", url)
            put("bearerToken", bearerToken)
        }
    }

    data class SRT(
        val host: String,
        val port: Int,
        val streamId: String,
        val passphrase: String,
        override val enabled: Boolean = true
    ) : OutputConfiguration() {
        override val displayName: String get() = "$host:$port"
        override val protocol: String get() = "SRT"
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
        override fun toJSON(): JSONObject = JSONObject().apply {
            put("type", "SRT")
            put("enabled", enabled)
            put("host", host)
            put("port", port)
            put("streamId", streamId)
            put("passphrase", passphrase)
        }
    }

    data class RTMP(
        val url: String,
        val streamKey: String,
        override val enabled: Boolean = true
    ) : OutputConfiguration() {
        override val displayName: String get() = url
        override val protocol: String get() = "RTMP"
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
        override fun toJSON(): JSONObject = JSONObject().apply {
            put("type", "RTMP")
            put("enabled", enabled)
            put("url", url)
            put("streamKey", streamKey)
        }
    }

    data class RTSP(
        val port: Int = 8554,
        override val enabled: Boolean = true
    ) : OutputConfiguration() {
        override val displayName: String get() = "Port $port"
        override val protocol: String get() = "RTSP"
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
        override fun toJSON(): JSONObject = JSONObject().apply {
            put("type", "RTSP")
            put("enabled", enabled)
            put("port", port)
        }
    }

    data class Disk(
        val path: String,
        override val enabled: Boolean = true
    ) : OutputConfiguration() {
        override val displayName: String get() = path
        override val protocol: String get() = "Disk"
        override fun withEnabled(enabled: Boolean) = copy(enabled = enabled)
        override fun toJSON(): JSONObject = JSONObject().apply {
            put("type", "Disk")
            put("enabled", enabled)
            put("path", path)
        }
    }

    companion object {
        fun fromJSON(json: JSONObject): OutputConfiguration {
            val type = json.getString("type")
            val enabled = json.optBoolean("enabled", true)
            return when (type) {
                "WHIP" -> WHIP(
                    url = json.getString("url"),
                    bearerToken = json.optString("bearerToken", ""),
                    enabled = enabled
                )
                "SRT" -> SRT(
                    host = json.getString("host"),
                    port = json.getInt("port"),
                    streamId = json.optString("streamId", ""),
                    passphrase = json.optString("passphrase", ""),
                    enabled = enabled
                )
                "RTMP" -> RTMP(
                    url = json.getString("url"),
                    streamKey = json.optString("streamKey", ""),
                    enabled = enabled
                )
                "RTSP" -> RTSP(
                    port = json.optInt("port", 8554),
                    enabled = enabled
                )
                "Disk" -> Disk(
                    path = json.getString("path"),
                    enabled = enabled
                )
                else -> throw IllegalArgumentException("Unknown output type: $type")
            }
        }

        fun listToJSON(configs: List<OutputConfiguration>): String {
            return JSONArray().apply {
                configs.forEach { put(it.toJSON()) }
            }.toString()
        }

        fun listFromJSON(json: String): List<OutputConfiguration> {
            if (json.isEmpty()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { fromJSON(array.getJSONObject(it)) }
            } catch (e: Exception) {
                // Handle legacy/invalid data
                emptyList()
            }
        }
    }
}

class Settings(private val dataStore: DataStore<Preferences>) {
    private val _codec = stringPreferencesKey("codec")
    private val _bitrate = intPreferencesKey("bitrate")
    private val _resolution = stringPreferencesKey("resolution")
    private val _outputConfigurations = stringPreferencesKey("output_configurations")
    private val _recordingMaxCapacityBytes = longPreferencesKey("recording_max_capacity_bytes")

    val codec = dataStore.data
         .map { SupportedVideoCodec.valueOf(it[_codec] ?: SupportedVideoCodec.H264.name) }

    suspend fun setCodec(codec: SupportedVideoCodec) {
        dataStore.edit { it[_codec] = codec.name }
    }

    val bitrate = dataStore.data.map { it[_bitrate] }

    suspend fun setBitrate(bitrate: Int) {
        dataStore.edit { it[_bitrate] = bitrate }
    }

    val resolution = dataStore.data.map {
        Resolution.fromString(it[_resolution] ?: "1920x1080")
    }

    suspend fun setResolution(resolution: Resolution) {
        dataStore.edit { it[_resolution] = resolution.toString() }
    }

    val outputConfigurations = dataStore.data
        .map { it[_outputConfigurations] }
        .map { json ->
            json?.let { OutputConfiguration.listFromJSON(it) } ?: emptyList()
        }

    suspend fun setOutputConfigurations(outputConfigurations: List<OutputConfiguration>) {
        dataStore.edit {
            it[_outputConfigurations] = OutputConfiguration.listToJSON(outputConfigurations)
        }
    }

    val recordingMaxCapacityBytes = dataStore.data.map {
        it[_recordingMaxCapacityBytes] ?: (1024L * 1024 * 1024)
    }

    suspend fun setRecordingMaxCapacityBytes(maxCapacityBytes: Long) {
        dataStore.edit { it[_recordingMaxCapacityBytes] = maxCapacityBytes }
    }

    suspend fun getStreamingConfiguration(): StreamingConfiguration {
//        val codec = codec.first().mimeType
        return StreamingConfiguration(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
    }
}
