package com.kevmo314.kineticstreamer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class SettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun settings() = Settings(
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "test.preferences_pb") }
    )

    @After
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun bytesToString_belowOneKiB() {
        assertEquals("0 B", bytesToString(0))
        assertEquals("1023 B", bytesToString(1023))
    }

    @Test
    fun bytesToString_scalesUnits() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1.0 KiB", bytesToString(1024))
            assertEquals("1.5 KiB", bytesToString(1536))
            assertEquals("1.0 MiB", bytesToString(1024L * 1024))
            assertEquals("1.0 GiB", bytesToString(1024L * 1024 * 1024))
            assertEquals("8.0 EiB", bytesToString(Long.MAX_VALUE))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun resolution_roundTrips() {
        val r = Resolution(1920, 1080)
        assertEquals("1920x1080", r.toString())
        assertEquals(r, Resolution.fromString(r.toString()))
    }

    @Test(expected = NumberFormatException::class)
    fun resolution_rejectsNonNumeric() {
        Resolution.fromString("axb")
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun resolution_rejectsMissingSeparator() {
        Resolution.fromString("1920")
    }

    @Test
    fun outputConfiguration_roundTripsUrlContainingColons() {
        val c = OutputConfiguration("srt://host:9000?streamid=a:b", true)
        assertEquals(c, OutputConfiguration.fromString(c.toString()))
        assertEquals("SRT", c.protocol)
    }

    @Test
    fun settings_defaults() = runBlocking {
        val s = settings()
        assertEquals(SupportedVideoCodec.H264, s.codec.first())
        assertEquals(Resolution(1920, 1080), s.resolution.first())
        assertEquals(1024L * 1024 * 1024, s.recordingMaxCapacityBytes.first())
        assertTrue(s.outputConfigurations.first().isEmpty())
        assertEquals(false, s.rotateVideo180.first())
    }

    @Test
    fun settings_persistsValues() = runBlocking {
        val s = settings()
        val outputs = listOf(
            OutputConfiguration("rtmp://a/b", true),
            OutputConfiguration("whip://c/d", false),
        )
        s.setCodec(SupportedVideoCodec.AV1)
        s.setResolution(Resolution(1280, 720))
        s.setBitrate(4_000_000)
        s.setOutputConfigurations(outputs)
        assertEquals(SupportedVideoCodec.AV1, s.codec.first())
        assertEquals(Resolution(1280, 720), s.resolution.first())
        assertEquals(4_000_000, s.bitrate.first())
        assertEquals(outputs, s.outputConfigurations.first())
    }

    @Test
    fun settings_persistsFlagsAndAudioDevice() = runBlocking {
        val s = settings()
        s.setRotateVideo180(true)
        s.setAutoOpenOnUsbCamera(true)
        s.setRecordingMaxCapacityBytes(42)
        s.setSelectedAudioDevice(7)
        assertEquals(true, s.rotateVideo180.first())
        assertEquals(true, s.autoOpenOnUsbCamera.first())
        assertEquals(42L, s.recordingMaxCapacityBytes.first())
        assertEquals(7, s.selectedAudioDevice.first())
        s.setSelectedAudioDevice(null)
        assertEquals(null, s.selectedAudioDevice.first())
    }
}
