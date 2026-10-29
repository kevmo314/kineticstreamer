package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Streams synthetic audio and video (see [SyntheticSources]) through the real renderer,
 * encoders, JNI and SRT sink to the host receiver in app/src/main/go/cmd/e2erecv, then asks
 * the receiver for its verdict: codecs, resolution, fps, monotonic PTS, keyframe and SPS/PPS
 * on the first frame, A/V sync, and recovery after the receiver cuts the connection.
 *
 * Start the receiver first (`go run ./cmd/e2erecv` in app/src/main/go). The emulator reaches
 * the host at 10.0.2.2. Without a receiver the test is skipped, unless the instrumentation
 * argument `e2eRequired=true` is given (CI does).
 */
@RunWith(AndroidJUnit4::class)
class StreamingE2ETest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val tag = "StreamingE2ETest"
    private val host = InstrumentationRegistry.getArguments().getString("e2eHost") ?: "10.0.2.2"
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var service: IStreamingService? = null
    private val bound = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IStreamingService.Stub.asInterface(binder)
            bound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // A raw socket: the app's network security policy forbids cleartext HttpURLConnection.
    private fun get(path: String, timeoutMs: Int): Pair<Int, String> {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, 9100), 3000)
            socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().decodeToString()
            return response.substringAfter("HTTP/1.0 ").substringBefore(' ').toInt() to response.substringAfter("\r\n\r\n")
        }
    }

    @Before
    fun requireReceiver() {
        val required = InstrumentationRegistry.getArguments().getString("e2eRequired") == "true"
        val up = runCatching { get("/health", 3000).first == 200 }
            .onFailure { Log.w(tag, "receiver health check failed", it) }.getOrDefault(false)
        if (required) assertTrue("e2e receiver not reachable at $host:9100", up) else assumeTrue("no e2e receiver", up)
    }

    @After
    fun tearDown() {
        StreamingService.captureOverride = null
        runCatching { service?.stopStreaming() }
        runCatching { context.unbindService(connection) }
        context.stopService(Intent(context, StreamingService::class.java))
    }

    @Test
    fun srtStreamIsCorrectSyncedAndRecoversFromReceiverRestart() {
        runBlocking {
            val outputs = Settings(DataStoreProvider.getDataStore(context)).outputs
            repeat(outputs.configurations.first().size) { outputs.remove(0) }
            outputs.add(OutputConfiguration("srt://$host:9000", true))
        }
        StreamingService.captureOverride = SyntheticSources().override()
        val intent = Intent(context, StreamingService::class.java)
        ContextCompat.startForegroundService(context, intent)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service did not bind", bound.await(15, TimeUnit.SECONDS))

        val svc = service!!
        svc.startStreaming()
        assertTrue("not streaming after start", svc.isStreaming)
        // The receiver cuts the first connection after 12s and is down for 4s, so this leaves
        // time for the sink to reconnect and for 8+ seconds of the second recording.
        Thread.sleep(34_000)
        svc.stopStreaming()

        val (status, body) = get("/report", 90_000)
        Log.i(tag, "receiver report ($status):\n$body")
        if (status != 200) fail("receiver reported failures:\n$body")
        assertEquals(200, status)
    }
}
