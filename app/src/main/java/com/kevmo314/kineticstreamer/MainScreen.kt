package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.ComponentName
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
    navigateToSettings: () -> Unit) {
    val permissionsState = rememberMultiplePermissionsState(REQUIRED_PERMISSIONS)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (!permissionsState.allPermissionsGranted) {
            Column {
                Text(
                    "Camera permission required for this feature to be available. " +
                            "Please grant the permission"
                )
                Button(onClick = {
                    permissionsState.launchMultiplePermissionRequest()
                }) {
                    Text("Request permission")
                }
            }
        } else {
            val cameraSelectorDialogOpen = remember { mutableStateOf(false) }
            val streamingService = remember { mutableStateOf<IStreamingService?>(null) }

            val applicationContext = LocalContext.current

            DisposableEffect(applicationContext) {
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(
                        className: ComponentName,
                        service: IBinder
                    ) {
                        streamingService.value =
                            IStreamingService.Stub.asInterface(service)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        streamingService.value = null
                    }
                }

                applicationContext.bindService(
                    Intent(
                        applicationContext,
                        StreamingService::class.java
                    ).apply {
                        action = IStreamingService::class.java.name
                    }, connection, Context.BIND_AUTO_CREATE
                )

                onDispose {
                    applicationContext.unbindService(connection)
                }
            }

            val stub = streamingService.value

            if (stub != null) {
                val isStreaming = remember(stub) { mutableStateOf(stub.isStreaming) }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SurfaceView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    Log.i("MainActivity", "surfaceCreated")

                                    streamingService.value?.setPreviewSurface(
                                        holder.surface
                                    )
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    Log.i("MainActivity", "surfaceChanged")
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    Log.i("MainActivity", "surfaceDestroyed")

                                    streamingService.value?.setPreviewSurface(null)
                                }
                            })
                        }
                    },
                )

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.safeGesturesPadding(),
                ) {
                    IconButton(
                        modifier = Modifier.size(64.dp).padding(16.dp),
                        onClick = {
                            // show camera selector dialog
                            cameraSelectorDialogOpen.value = true
                        },
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.Cameraswitch,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().offset(1.dp, 1.dp),
                                tint = Color.Black.copy(alpha = 0.5f),
                            )
                            Icon(
                                Icons.Filled.Cameraswitch,
                                contentDescription = "Switch camera",
                                modifier = Modifier.fillMaxSize(),
                                tint = Color.White,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (isStreaming.value) {
                                Log.i("MainScreen", "Stopping streaming")
                                stub.stopStreaming()
                                isStreaming.value = false
                            } else {
                                runBlocking {
                                    val streamingConfig = settings.getStreamingConfiguration()
                                    val outputConfigs = settings.outputConfigurations.first()
                                    val outputConfigsJson = OutputConfiguration.listToJSON(outputConfigs)
                                    Log.i("MainScreen", "Starting streaming with config: $outputConfigsJson")
                                    val error = stub.startStreaming(streamingConfig, outputConfigsJson)
                                    if (error != null) {
                                        Log.e("MainScreen", "Streaming error: $error")
                                    }
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    ) {
                    }
                    IconButton(
                        modifier = Modifier.size(64.dp).padding(16.dp),
                        onClick = {
                            navigateToSettings()
                        },
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().offset(1.dp, 1.dp),
                                tint = Color.Black.copy(alpha = 0.5f),
                            )
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier.fillMaxSize(),
                                tint = Color.White,
                            )
                        }
                    }
                }

                if (cameraSelectorDialogOpen.value) {
                    CameraSelector(
                        onDismissRequest = {
                            cameraSelectorDialogOpen.value = false
                        },
                        selectedCameraId = stub.activeCameraId,
                        onCameraSelected = {
                            stub.activeCameraId = it
                        },
                    )
                }
            }
        }
    }
}