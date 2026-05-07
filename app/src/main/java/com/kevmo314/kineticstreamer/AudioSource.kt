package com.kevmo314.kineticstreamer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioTimestamp
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread

/**
 * Audio data with capture timestamp for A/V synchronization.
 */
data class TimestampedAudio(
    val data: ByteArray,
    val captureTimeNanos: Long,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT
)

/**
 * Creates a Flow of timestamped audio data from the specified audio device.
 *
 * @param context Android context
 * @param deviceId The audio device ID from AudioDeviceInfo, or null for default microphone
 * @return Flow of PCM audio data with capture timestamps
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
fun AudioSource(context: Context, deviceId: Int?): Flow<TimestampedAudio> = callbackFlow  {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Find the audio device if an ID was provided
    val audioDevice = if (deviceId != null) {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .find { it.id == deviceId }
    } else {
        null
    }

    val deviceName = audioDevice?.productName?.toString() ?: "Default Microphone"
    Log.i("AudioSource", "Setting up audio recording for: $deviceName")
    if (audioDevice != null) {
        Log.i(
            "AudioSource",
            "Device capabilities: sampleRates=${audioDevice.sampleRates.contentToString()}, " +
                "channelCounts=${audioDevice.channelCounts.contentToString()}, " +
                "encodings=${audioDevice.encodings.contentToString()}"
        )
    }

    // Audio recording parameters
    val sampleRate = 48000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
        Log.e("AudioSource", "Invalid buffer size for AudioRecord")
        close()
        return@callbackFlow
    }

    try {
        // Use UNPROCESSED to avoid high-pass filtering and voice processing
        // that causes "tinny" sound by removing bass frequencies.
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2 // Use 2x min buffer size for stability
        )

        // Set preferred device if specified
        if (audioDevice != null) {
            val success = audioRecord.setPreferredDevice(audioDevice)
            Log.i("AudioSource", "Set preferred audio device: $success for $deviceName")
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioSource", "AudioRecord failed to initialize for device: $deviceName")
            close()
            return@callbackFlow
        }

        audioRecord.startRecording()
        val actualSampleRate = audioRecord.sampleRate
        val actualChannelCount = audioRecord.channelCount
        val actualEncoding = audioRecord.audioFormat
        Log.i(
            "AudioSource",
            "Audio recording started for: $deviceName " +
                "(sampleRate=$actualSampleRate, channels=$actualChannelCount, encoding=$actualEncoding)"
        )

        // Create recording thread
        val recordThread = thread(name = "AudioRecord-Source") {
            try {
                val buffer = ByteArray(bufferSize)
                var frameCount = 0
                var totalBytes = 0
                var totalSamplesRead = 0L
                val audioTimestamp = AudioTimestamp()
                var startRecordingTimeNanos = System.nanoTime() // Fallback

                while (!Thread.currentThread().isInterrupted) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)

                    if (bytesRead > 0) {
                        frameCount++
                        totalBytes += bytesRead
                        val framesInThisRead = bytesRead / (2 * actualChannelCount.coerceAtLeast(1))

                        // Calculate timestamp using AudioRecord.getTimestamp() for hardware-accurate timing
                        val status = audioRecord.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                        val captureTimeNanos = if (status == AudioRecord.SUCCESS) {
                            // Calculate timestamp for the START of this buffer
                            // audioTimestamp gives us an anchor: framePosition <-> nanoTime
                            val frameDiff = totalSamplesRead - audioTimestamp.framePosition
                            audioTimestamp.nanoTime + (frameDiff * 1_000_000_000L / actualSampleRate)
                        } else {
                            // Fallback: calculate based on samples read
                            startRecordingTimeNanos + (totalSamplesRead * 1_000_000_000L / actualSampleRate)
                        }

                        totalSamplesRead += framesInThisRead

                        // Log first frame for debugging
                        if (frameCount == 1) {
                            val sample = buffer.take(16).joinToString(" ") { "%02x".format(it) }
                            Log.i("AudioSource", "First audio frame (${bytesRead} bytes) from $deviceName: $sample...")
                        }

                        // Create a copy of the exact size read
                        val audioData = buffer.copyOfRange(0, bytesRead)

                        // Send timestamped audio data to flow
                        trySend(TimestampedAudio(audioData, captureTimeNanos, actualSampleRate, actualChannelCount, actualEncoding))
                    } else if (bytesRead < 0) {
                        Log.e("AudioSource", "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioSource", "Error in audio recording: ${e.message}", e)
            }
        }

        awaitClose {
            Log.i("AudioSource", "Closing audio source: $deviceName")
            recordThread.interrupt()
            recordThread.join(1000) // Wait max 1 second for thread to finish
            audioRecord.stop()
            audioRecord.release()
        }

    } catch (e: Exception) {
        Log.e("AudioSource", "Error setting up audio recording: ${e.message}", e)
        close()
    }
}
