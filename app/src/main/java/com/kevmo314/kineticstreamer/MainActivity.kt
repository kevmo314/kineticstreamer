package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.camera.core.processing.OpenGlRenderer
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kevmo314.kineticstreamer.ui.theme.KineticStreamerTheme
import kotlinx.coroutines.launch


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
                        val cameraManager =
                            applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val selectedCameraId =
                            remember { mutableStateOf(cameraManager.cameraIdList[0]) }

                        LaunchedEffect(Unit) {
                            startForegroundService(
                                Intent(
                                    applicationContext,
                                    StreamingService::class.java
                                )
                            )
                        }

                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                val surfaceView = SurfaceView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }

                                bindService(
                                    Intent(
                                        applicationContext,
                                        StreamingService::class.java
                                    ).apply {
                                        action = IStreamingService::class.java.name
                                    }, object : ServiceConnection {
                                        override fun onServiceConnected(
                                            className: ComponentName,
                                            service: IBinder
                                        ) {
                                            IStreamingService.Stub.asInterface(service)
                                                .setPreviewSurface(
                                                    surfaceView.holder.surface
                                                )
                                        }

                                        override fun onServiceDisconnected(name: ComponentName?) {
                                            TODO("Not yet implemented")
                                        }
                                    }, Context.BIND_AUTO_CREATE
                                )

                                surfaceView
                            }
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
                            TextButton({
                            }) {
                                Text("Start streaming")
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
                                selectedCameraId = selectedCameraId.value,
                                onCameraSelected = {
                                    selectedCameraId.value = it
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
