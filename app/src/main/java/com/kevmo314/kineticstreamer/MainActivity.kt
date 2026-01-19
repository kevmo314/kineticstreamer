package com.kevmo314.kineticstreamer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.kevmo314.kineticstreamer.ui.theme.KineticStreamerTheme
import kotlinx.coroutines.flow.first

fun UsbDevice.isUvc(): Boolean {
    for (i in 0 until configurationCount) {
        val configuration = getConfiguration(i)
        for (interfaceIndex in 0 until configuration.interfaceCount) {
            val interfaceDescriptor = configuration.getInterface(interfaceIndex)
            if (interfaceDescriptor.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
    }
    return false
}

fun UsbDevice.isUac(): Boolean {
    for (i in 0 until configurationCount) {
        val configuration = getConfiguration(i)
        for (interfaceIndex in 0 until configuration.interfaceCount) {
            val interfaceDescriptor = configuration.getInterface(interfaceIndex)
            if (interfaceDescriptor.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
            Log.i("KineticStreamer", "Interface: ${interfaceDescriptor.interfaceClass}")
        }
    }
    return false
}

class MainActivity : ComponentActivity() {
    private val streamingService = mutableStateOf<IStreamingService?>(null)
    private var serviceConnection: ServiceConnection? = null

    override fun onResume() {
        super.onResume()

        if (intent != null && intent.action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, Intent(this, StreamingService.UsbReceiver::class.java).apply {
                    action = StreamingService.UsbReceiver.ACTION_USB_PERMISSION
                },
                PendingIntent.FLAG_MUTABLE
            )
            intent.getParcelableExtra<UsbDevice?>(UsbManager.EXTRA_DEVICE).apply {
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                manager.requestPermission(this, pendingIntent)
            }
        }
    }

    @ExperimentalMaterial3Api
    @ExperimentalPermissionsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        super.onCreate(savedInstanceState)

        // Bind to streaming service at activity level so it persists across navigation
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                streamingService.value = IStreamingService.Stub.asInterface(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                streamingService.value = null
            }
        }

        bindService(
            Intent(this, StreamingService::class.java).apply {
                action = IStreamingService::class.java.name
            },
            serviceConnection!!,
            Context.BIND_AUTO_CREATE
        )

        // Debug orientation info
        val display = windowManager.defaultDisplay
        val rotation = display.rotation
        val displayMetrics = resources.displayMetrics
        Log.i("KineticStreamer", "Display rotation: $rotation (0=0°, 1=90°, 2=180°, 3=270°)")
        Log.i("KineticStreamer", "Display size: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
        Log.i("KineticStreamer", "Requested orientation: $requestedOrientation")
        Log.i("KineticStreamer", "Current orientation: ${resources.configuration.orientation} (1=portrait, 2=landscape)")

        val dataStore = DataStoreProvider.getDataStore(this)
        val settings = Settings(dataStore)

        setContent {
            val navController = rememberNavController()

            KineticStreamerTheme {
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            settings = settings,
                            streamingService = streamingService.value,
                            navigateToSettings = {
                                navController.navigate("settings")
                            })
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            navigateBack = {
                                navController.popBackStack()
                            },
                            navigateTo = {
                                navController.navigate(it)
                            })
                    }
                    composable("settings/recording") {
                        RecordingSettingsScreen(settings = settings, navigateBack = {
                            navController.popBackStack()
                        })
                    }
                    composable("settings/output") {
                        AddOutputScreen(
                            navigateTo = { navController.navigate(it) },
                            navigateBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/output/whip") {
                        AddWhipOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val configs = settings.outputConfigurations.first().toMutableList()
                                    configs.add(config)
                                    settings.setOutputConfigurations(configs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) }
                        )
                    }
                    composable("settings/output/srt") {
                        AddSrtOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val configs = settings.outputConfigurations.first().toMutableList()
                                    configs.add(config)
                                    settings.setOutputConfigurations(configs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) }
                        )
                    }
                    composable("settings/output/rtmp") {
                        AddRtmpOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val configs = settings.outputConfigurations.first().toMutableList()
                                    configs.add(config)
                                    settings.setOutputConfigurations(configs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) }
                        )
                    }
                    composable("settings/output/rtsp") {
                        AddRtspOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val configs = settings.outputConfigurations.first().toMutableList()
                                    configs.add(config)
                                    settings.setOutputConfigurations(configs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) }
                        )
                    }
                    composable("settings/output/disk") {
                        AddDiskOutputScreen(
                            onSave = { config ->
                                kotlinx.coroutines.runBlocking {
                                    val configs = settings.outputConfigurations.first().toMutableList()
                                    configs.add(config)
                                    settings.setOutputConfigurations(configs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) }
                        )
                    }
                    // Edit routes
                    composable("settings/output/whip/edit/{index}") { backStackEntry ->
                        val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: return@composable
                        val configs = kotlinx.coroutines.runBlocking { settings.outputConfigurations.first() }
                        if (index >= configs.size) return@composable
                        val config = configs[index]
                        // Parse whip://url?token=token format
                        val rawUrl = config.url.removePrefix("whip://")
                        val uri = android.net.Uri.parse(rawUrl)
                        val token = uri.getQueryParameter("token") ?: ""
                        val cleanUrl = uri.buildUpon().clearQuery().build().toString()

                        AddWhipOutputScreen(
                            onSave = { newConfig ->
                                kotlinx.coroutines.runBlocking {
                                    val currentConfigs = settings.outputConfigurations.first().toMutableList()
                                    currentConfigs[index] = newConfig
                                    settings.setOutputConfigurations(currentConfigs)
                                }
                            },
                            onDelete = {
                                kotlinx.coroutines.runBlocking {
                                    val currentConfigs = settings.outputConfigurations.first().toMutableList()
                                    currentConfigs.removeAt(index)
                                    settings.setOutputConfigurations(currentConfigs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) },
                            initialUrl = cleanUrl,
                            initialToken = token
                        )
                    }
                    composable("settings/output/srt/edit/{index}") { backStackEntry ->
                        val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: return@composable
                        val configs = kotlinx.coroutines.runBlocking { settings.outputConfigurations.first() }
                        if (index >= configs.size) return@composable
                        val config = configs[index]
                        // Parse srt://host:port?streamid=id format
                        val uri = android.net.Uri.parse(config.url)
                        val host = uri.host ?: ""
                        val port = uri.port.takeIf { it > 0 }?.toString() ?: "9000"
                        val streamId = uri.getQueryParameter("streamid") ?: ""

                        AddSrtOutputScreen(
                            onSave = { newConfig ->
                                kotlinx.coroutines.runBlocking {
                                    val currentConfigs = settings.outputConfigurations.first().toMutableList()
                                    currentConfigs[index] = newConfig
                                    settings.setOutputConfigurations(currentConfigs)
                                }
                            },
                            onDelete = {
                                kotlinx.coroutines.runBlocking {
                                    val currentConfigs = settings.outputConfigurations.first().toMutableList()
                                    currentConfigs.removeAt(index)
                                    settings.setOutputConfigurations(currentConfigs)
                                }
                            },
                            navigateBack = { navController.popBackStack("settings", false) },
                            initialHost = host,
                            initialPort = port,
                            initialStreamId = streamId
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        serviceConnection?.let { unbindService(it) }
        super.onDestroy()
    }
}
