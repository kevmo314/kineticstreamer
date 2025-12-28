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

data class OutputConfiguration(val url: String, val enabled: Boolean) {
    override fun toString(): String = "$enabled:$url"

    val protocol: String
        get() = url.split(":", limit=2).first().uppercase()

    companion object {
        fun fromString(string: String): OutputConfiguration {
            val (enabled, url) = string.split(":", limit=2)
            return OutputConfiguration(url, enabled.toBoolean())
        }
    }
}

class Settings(private val dataStore: DataStore<Preferences>) {
    private val _codec = stringPreferencesKey("codec")
    private val _bitrate = intPreferencesKey("bitrate")
    private val _resolution = stringPreferencesKey("resolution")
    private val _outputConfigurations = stringPreferencesKey("output_configurations")
    private val _recordingMaxCapacityBytes = longPreferencesKey("recording_max_capacity_bytes")
    private val _selectedVideoDevice = stringPreferencesKey("selected_video_device")
    private val _selectedAudioDevice = intPreferencesKey("selected_audio_device_id")

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
        .map {
            it?.split(";")?.map { s -> OutputConfiguration.fromString(s) } ?: listOf()
        }

    suspend fun setOutputConfigurations(outputConfigurations: List<OutputConfiguration>) {
        dataStore.edit {
            it[_outputConfigurations] = outputConfigurations.joinToString(";") { c -> c.toString() }
        }
    }

    val recordingMaxCapacityBytes = dataStore.data.map {
        it[_recordingMaxCapacityBytes] ?: (1024L * 1024 * 1024)
    }

    suspend fun setRecordingMaxCapacityBytes(maxCapacityBytes: Long) {
        dataStore.edit { it[_recordingMaxCapacityBytes] = maxCapacityBytes }
    }
    
    val selectedVideoDevice = dataStore.data.map { it[_selectedVideoDevice] }
    
    suspend fun setSelectedVideoDevice(device: VideoSourceDevice?) {
        dataStore.edit { 
            if (device != null) {
                it[_selectedVideoDevice] = device.identifier
            } else {
                it.remove(_selectedVideoDevice)
            }
        }
    }

    val selectedAudioDevice = dataStore.data.map { it[_selectedAudioDevice] }

    suspend fun setSelectedAudioDevice(deviceId: Int?) {
        dataStore.edit {
            if (deviceId != null) {
                it[_selectedAudioDevice] = deviceId
            } else {
                it.remove(_selectedAudioDevice)
            }
        }
    }

    suspend fun getStreamingConfiguration(): StreamingConfiguration {
//        val codec = codec.first().mimeType
        return StreamingConfiguration(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        )
    }
}
