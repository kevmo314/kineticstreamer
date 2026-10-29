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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starts/stops streaming repeatedly against an output that cannot connect and checks that
 * the service survives, reports not-streaming after the failed start, and doesn't leak threads.
 */
@RunWith(AndroidJUnit4::class)
class StreamingServiceLifecycleTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var service: IStreamingService? = null
    private val connection = object : ServiceConnection {
        val bound = CountDownLatch(1)
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IStreamingService.Stub.asInterface(binder)
            bound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @Before
    fun setUp() {
        runBlocking {
            Settings(DataStoreProvider.getDataStore(context))
                .setOutputConfigurations(listOf(OutputConfiguration("srt://127.0.0.1:1", true)))
        }
        val intent = Intent(context, StreamingService::class.java)
        ContextCompat.startForegroundService(context, intent)
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("service did not bind", connection.bound.await(15, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        runCatching { service?.stopStreaming() }
        context.unbindService(connection)
        context.stopService(Intent(context, StreamingService::class.java))
    }

    private fun threadCount() = File("/proc/self/task").list()?.size ?: -1

    @Test
    fun repeatedFailedStartsDoNotLeakOrReportStreaming() {
        val svc = service!!
        val config = StreamingConfiguration("video/avc", "audio/mp4a-latm", 0)

        // Warm-up so one-time threads (binder, native, dispatchers) are already counted.
        svc.startStreaming(config)
        svc.stopStreaming()
        Thread.sleep(2000)
        val baseline = threadCount()
        Log.i("LifecycleTest", "baseline threads=$baseline")

        val counts = mutableListOf<Int>()
        repeat(8) { i ->
            svc.startStreaming(config)
            assertFalse("cycle $i: streaming after failed start", svc.isStreaming)
            svc.stopStreaming()
            assertFalse("cycle $i: streaming after stop", svc.isStreaming)
            Thread.sleep(500)
            counts += threadCount()
            Log.i("LifecycleTest", "cycle $i threads=${counts.last()}")
        }
        Thread.sleep(2000)
        val after = threadCount()
        Log.i("LifecycleTest", "after threads=$after (baseline=$baseline, per-cycle=$counts)")
        assertTrue("thread count grew: baseline=$baseline after=$after counts=$counts", after <= baseline + 2)
    }
}
