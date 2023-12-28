package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kevmo314.kineticstreamer.ui.theme.KineticStreamerTheme

class MainActivity : ComponentActivity() {
    companion object {
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
    }

    @ExperimentalPermissionsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        super.onCreate(savedInstanceState)

        setContent {
            val permissionsState = rememberMultiplePermissionsState(REQUIRED_PERMISSIONS)

            KineticStreamerTheme {
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

                            bindService(
                                Intent(
                                    applicationContext,
                                    StreamingService::class.java
                                ).apply {
                                    action = IStreamingService::class.java.name
                                }, connection, Context.BIND_AUTO_CREATE
                            )

                            onDispose {
                                unbindService(connection)
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
                                    modifier = Modifier.size(32.dp),
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
                                        } else {
                                            stub.startStreaming()
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
                                TextButton({
                                }) {
                                    Text("Stoasdfasdfasdfp")
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
        }
    }
}
