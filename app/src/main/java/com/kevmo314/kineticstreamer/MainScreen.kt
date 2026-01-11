package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

val REQUIRED_PERMISSIONS =
    mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
@ExperimentalPermissionsApi
@Composable
fun MainScreen(
    settings: Settings,
    streamingService: IStreamingService?,
    navigateToSettings: () -> Unit) {
    val permissionsState = rememberMultiplePermissionsState(REQUIRED_PERMISSIONS)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        if (!permissionsState.allPermissionsGranted) {
            LaunchedEffect(Unit) {
                permissionsState.launchMultiplePermissionRequest()
            }
        } else {
            val cameraSelectorDialogOpen = remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            val applicationContext = LocalContext.current

            val stub = streamingService
            val previewSurface = remember { mutableStateOf<Surface?>(null) }

            // Set preview surface when both stub and surface are available
            LaunchedEffect(stub, previewSurface.value) {
                val surface = previewSurface.value
                if (stub != null && surface != null && surface.isValid) {
                    Log.i("KineticStreamer", "Setting preview surface from LaunchedEffect: $surface")
                    stub.setPreviewSurface(surface)
                }
            }

            if (stub != null) {
                val isStreaming = remember(stub) { mutableStateOf(stub.isStreaming) }
                val currentBitrate = remember { mutableIntStateOf(0) }
                val currentFps = remember { mutableStateOf(0f) }
                val audioLevels = remember { mutableStateOf(emptyList<Float>()) }
                
                // Set up audio level callback
                LaunchedEffect(stub) {
                    stub.setAudioLevelCallback(object : IAudioLevelCallback.Stub() {
                        override fun onAudioLevels(levels: FloatArray?) {
                            levels?.let {
                                audioLevels.value = it.toList()
                            }
                        }
                    })
                }
                
                // Periodically update bitrate and FPS when streaming
                LaunchedEffect(isStreaming.value) {
                    if (isStreaming.value) {
                        while (isStreaming.value) {
                            currentBitrate.intValue = stub.currentBitrate
                            currentFps.value = stub.currentFps
                            delay(250) // Update 4 times per second
                        }
                    } else {
                        currentBitrate.intValue = 0
                        currentFps.value = 0f
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Fit within screen maintaining 16:9 aspect ratio (letterbox/pillarbox)
                    val videoAspectRatio = 16f / 9f

                    AndroidView(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .aspectRatio(videoAspectRatio),
                        factory = { context ->
                            SurfaceView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                // Force landscape buffer dimensions
                                holder.setFixedSize(1920, 1080)
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        val frame = holder.surfaceFrame
                                        Log.i("KineticStreamer", "surfaceCreated: frame=${frame.width()}x${frame.height()}, surface=${holder.surface}")
                                        // Store surface - LaunchedEffect will set it on the service
                                        previewSurface.value = holder.surface
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int
                                    ) {
                                        Log.i("KineticStreamer", "surfaceChanged: ${width}x${height} format=$format")
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        previewSurface.value = null
                                        streamingService?.setPreviewSurface(null)
                                    }
                                })
                            }
                        },
                    )
                    
                    // Top left: Audio meter, Bitrate, and FPS
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .safeGesturesPadding()
                    ) {
                        // Audio visualizer
                        AudioVisualizerView(
                            audioLevels = audioLevels.value,
                            barColor = Color.Green,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        if (isStreaming.value && (currentBitrate.intValue > 0 || currentFps.value > 0)) {
                            if (currentBitrate.intValue > 0) {
                                Text(
                                    text = "${currentBitrate.intValue / 1000} Kbps",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (currentFps.value > 0) {
                                Text(
                                    text = "%.1f fps".format(currentFps.value),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Right side controls - vertically centered
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .safeGesturesPadding()
                            .padding(end = 16.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            IconButton(
                                modifier = Modifier.size(64.dp).padding(16.dp),
                                onClick = {
                                    // show camera selector dialog
                                    cameraSelectorDialogOpen.value = true
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Cameraswitch,
                                    contentDescription = "Switch camera",
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.White,
                                )
                            }
                            Button(
                                onClick = {
                                    if (isStreaming.value) {
                                        stub.stopStreaming()
                                        isStreaming.value = false
                                    } else {
                                        runBlocking {
                                            stub.startStreaming(settings.getStreamingConfiguration())
                                            isStreaming.value = true
                                        }
                                    }
                                },
                                modifier = Modifier.size(64.dp),
                                shape = if (isStreaming.value) {
                                    RoundedCornerShape(2.dp)
                                } else {
                                    CircleShape
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isStreaming.value) Color.Green else Color.Red
                                ),
                            ) {
                            }
                            IconButton(
                                modifier = Modifier.size(64.dp).padding(16.dp),
                                onClick = {
                                    navigateToSettings()
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }

                if (cameraSelectorDialogOpen.value) {
                    val selectedAudioDeviceId = remember { mutableStateOf<Int?>(null) }
                    val selectedVideoDevice = remember { mutableStateOf<VideoSourceDevice?>(null) }
                    val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                    
                    // Load selected devices from settings
                    LaunchedEffect(Unit) {
                        // Load audio device
                        settings.selectedAudioDevice.collect { deviceId ->
                            selectedAudioDeviceId.value = deviceId
                        }
                    }
                    
                    LaunchedEffect(Unit) {
                        // Load video device
                        settings.selectedVideoDevice.collect { identifier ->
                            if (identifier != null) {
                                val usbDevices = usbManager.deviceList.values.toList()
                                selectedVideoDevice.value = VideoSourceDevice.fromIdentifier(identifier, usbDevices)
                            } else {
                                selectedVideoDevice.value = null
                            }
                        }
                    }
                    
                    DeviceSelector(
                        onDismissRequest = {
                            cameraSelectorDialogOpen.value = false
                        },
                        selectedVideoDevice = selectedVideoDevice.value,
                        onVideoDeviceSelected = { device ->
                            println("Selected video device: $device")
                            
                            // Save the video selection to Settings
                            coroutineScope.launch {
                                settings.setSelectedVideoDevice(device)
                                selectedVideoDevice.value = device
                            }
                        },
                        selectedAudioDeviceId = selectedAudioDeviceId.value,
                        onAudioDeviceSelected = { deviceId ->
                            // Save the audio selection to Settings
                            coroutineScope.launch {
                                settings.setSelectedAudioDevice(deviceId)
                                selectedAudioDeviceId.value = deviceId
                            }
                        }
                    )
                }
            }
        }
    }
}