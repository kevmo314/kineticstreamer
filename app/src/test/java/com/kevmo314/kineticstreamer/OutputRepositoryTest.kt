package com.kevmo314.kineticstreamer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OutputConfigurationTest {
    private val nasty = listOf(
        "srt://host:9000?streamid=a;b:c",
        "whip://https://example.com/whip?token=se;cr:et%41&x=1",
        "rtmp://日本語.example/ライブ/ключ",
        "file://rec ording+1",
        "rtsp://localhost:8554/stream\nnewline",
    )

    @Test
    fun roundTripsSeparatorsUnicodeAndTokens() {
        val outputs = nasty.mapIndexed { i, url -> OutputConfiguration(url, i % 2 == 0) }
        assertEquals(outputs, decodeOutputs(encodeOutputs(outputs)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<OutputConfiguration>(), decodeOutputs(encodeOutputs(emptyList())))
        assertEquals(emptyList<OutputConfiguration>(), decodeOutputs(null))
    }

    @Test
    fun legacyFormatIsReadVerbatim() {
        val legacy = "true:srt://host:9000?streamid=abc;false:rtmp://a.b/live/key%20x"
        assertEquals(
            listOf(
                OutputConfiguration("srt://host:9000?streamid=abc", true),
                OutputConfiguration("rtmp://a.b/live/key%20x", false),
            ),
            decodeOutputs(legacy, legacy = true),
        )
    }

    @Test
    fun malformedEntriesAreSkipped() {
        val encoded = listOf(
            "", "garbage", "maybe:srt%3A%2F%2Fh", "true:", "true:%zz", "true:srt%3A%2F%2Fok", ":x",
        ).joinToString(";")
        assertEquals(listOf(OutputConfiguration("srt://ok", true)), decodeOutputs(encoded))
        assertEquals(emptyList<OutputConfiguration>(), decodeOutputs("garbage;;x", legacy = true))
    }

    @Test
    fun protocolIsUppercasedScheme() {
        assertEquals("SRT", OutputConfiguration("srt://h:1", true).protocol)
        assertEquals("FILE", OutputConfiguration("file://x", true).protocol)
    }
}

class OutputTypeTest {
    @Test
    fun everyStoredSchemeMapsToAnEditableType() {
        assertEquals(OutputType.WHIP, OutputType.fromUrl("whip://https://e.com/w?token=t"))
        assertEquals(OutputType.WHIP, OutputType.fromUrl("https://e.com/w"))
        assertEquals(OutputType.SRT, OutputType.fromUrl("srt://h:1"))
        assertEquals(OutputType.RIST, OutputType.fromUrl("rist://h:1"))
        assertEquals(OutputType.RTMP, OutputType.fromUrl("rtmp://h/live/key"))
        assertEquals(OutputType.RTSP, OutputType.fromUrl("rtsp://localhost:8554/stream"))
        assertEquals(OutputType.DISK, OutputType.fromUrl("file://rec"))
        assertNull(OutputType.fromUrl("ftp://nope"))
    }

    @Test
    fun routesRoundTrip() {
        for (type in OutputType.entries) assertEquals(type, OutputType.fromRoute(type.route))
        assertNull(OutputType.fromRoute("bogus"))
        assertNull(OutputType.fromRoute(null))
    }

    @Test
    fun rtspUrlParsesIntoPortAndPath() {
        assertEquals("8554" to "/stream", parseRtspUrl("rtsp://localhost:8554/stream"))
        assertEquals("1" to "/a/b", parseRtspUrl("rtsp://localhost:1/a/b"))
        assertEquals("9000" to "", parseRtspUrl("rtsp://localhost:9000"))
    }
}

class OutputRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "test.preferences_pb") }
    }
    private val repo by lazy { OutputRepository(dataStore) }

    @After
    fun tearDown() = scope.cancel()

    private fun list() = runBlocking { repo.configurations.first() }

    @Test
    fun addUpdateRemove() = runBlocking {
        assertEquals(emptyList<OutputConfiguration>(), list())

        repo.add(OutputConfiguration("srt://a;b:1", true))
        repo.add(OutputConfiguration("rtmp://c/d", true))
        repo.add(OutputConfiguration("file://e", true))
        assertEquals(listOf("srt://a;b:1", "rtmp://c/d", "file://e"), list().map { it.url })

        repo.update(1) { it.copy(enabled = false) }
        assertEquals(listOf(true, false, true), list().map { it.enabled })

        repo.update(2) { OutputConfiguration("file://f", it.enabled) }
        assertEquals("file://f", list()[2].url)

        repo.remove(0)
        assertEquals(listOf("rtmp://c/d", "file://f"), list().map { it.url })
    }

    @Test
    fun outOfRangeIndexIsNoOp() = runBlocking {
        repo.add(OutputConfiguration("srt://a:1", true))
        repo.update(5) { it.copy(enabled = false) }
        repo.update(-1) { it.copy(enabled = false) }
        repo.remove(5)
        repo.remove(-1)
        assertEquals(listOf(OutputConfiguration("srt://a:1", true)), list())
    }

    @Test
    fun concurrentAddsAreAllKept() = runBlocking {
        val jobs = (0 until 20).map { i ->
            scope.launch { repo.add(OutputConfiguration("srt://h:$i", true)) }
        }
        jobs.forEach { it.join() }
        assertEquals(20, list().size)
        assertEquals((0 until 20).map { "srt://h:$it" }.toSet(), list().map { it.url }.toSet())
    }

    @Test
    fun legacyDataIsReadThenMigratedOnFirstWrite() = runBlocking {
        val legacyKey = stringPreferencesKey("output_configurations")
        dataStore.edit { it[legacyKey] = "true:srt://h:9000?streamid=x;false:file://rec" }
        assertEquals(listOf("srt://h:9000?streamid=x", "file://rec"), list().map { it.url })

        repo.update(1) { it.copy(enabled = true) }
        assertEquals(listOf(true, true), list().map { it.enabled })
        assertNull(dataStore.data.first()[legacyKey])
    }

    @Test
    fun corruptStoredValueDoesNotCrash() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("output_configurations")] = ";;nonsense;true" }
        assertEquals(emptyList<OutputConfiguration>(), list())
        repo.add(OutputConfiguration("file://ok", true))
        assertEquals(listOf("file://ok"), list().map { it.url })
    }
}
