package com.kevmo314.kineticstreamer

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking

@ExperimentalMaterial3Api
@Composable
fun CodecRadioButton(
    selectedCodec: SupportedVideoCodec?,
    codec: SupportedVideoCodec,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(selected = selectedCodec == codec, onClick = onClick)
            Text(codec.name)
        }
    }
}

@ExperimentalMaterial3Api
@Composable
fun SettingsScreen(
    settings: Settings,
    streamingService: IStreamingService?,
    navigateBack: () -> Unit,
    navigateTo: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("Settings")
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
    ) {
        Column(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text("Outputs", modifier = Modifier.padding(16.dp))

            val outputConfigurations = settings.outputConfigurations.collectAsState(initial = emptyList())

            for ((index, outputConfiguration) in outputConfigurations.value.withIndex()) {
                Surface(onClick = {
                    // Navigate to edit screen based on protocol
                    when {
                        outputConfiguration.url.startsWith("whip://") -> navigateTo("settings/output/whip/edit/$index")
                        outputConfiguration.url.startsWith("srt://") -> navigateTo("settings/output/srt/edit/$index")
                        outputConfiguration.url.startsWith("rtmp://") -> navigateTo("settings/output/rtmp/edit/$index")
                        outputConfiguration.url.startsWith("rtsp://") -> navigateTo("settings/output/rtsp/edit/$index")
                        outputConfiguration.url.startsWith("file://") -> navigateTo("settings/output/disk/edit/$index")
                    }
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = outputConfiguration.enabled, onCheckedChange = {
                            runBlocking {
                                val updatedConfigurations = outputConfigurations.value.toMutableList()
                                updatedConfigurations[index] = outputConfiguration.copy(enabled = !outputConfiguration.enabled)
                                settings.setOutputConfigurations(updatedConfigurations)
                            }
                        })
                        Column {
                            Text(outputConfiguration.protocol)
                            Text(outputConfiguration.url)
                        }
                    }
                }
            }

            Surface(onClick = { navigateTo("settings/output") }) {
                ListItem(
                    headlineContent = { Text("Add Output") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add output"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Divider()

            Text("Codec", modifier = Modifier.padding(16.dp))

            val codec = settings.codec.collectAsState(initial = null)

            CodecRadioButton(codec.value, SupportedVideoCodec.H264) {
                runBlocking {
                    settings.setCodec(SupportedVideoCodec.H264)
                }
            }
            CodecRadioButton(codec.value, SupportedVideoCodec.H265) {
                runBlocking {
                    settings.setCodec(SupportedVideoCodec.H265)
                }
            }
            CodecRadioButton(codec.value, SupportedVideoCodec.VP8) {
                runBlocking {
                    settings.setCodec(SupportedVideoCodec.VP8)
                }
            }
            CodecRadioButton(codec.value, SupportedVideoCodec.VP9) {
                runBlocking {
                    settings.setCodec(SupportedVideoCodec.VP9)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                CodecRadioButton(codec.value, SupportedVideoCodec.AV1) {
                    runBlocking {
                        settings.setCodec(SupportedVideoCodec.AV1)
                    }

                }
            }
            Surface(onClick = { /*TODO*/ }) {
                ListItem(
                    headlineContent = { Text("Bitrate") },
                    supportingContent = { Text("Adaptive bitrate") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(onClick = {
                navigateTo("settings/recording")
            }) {
                ListItem(
                    headlineContent = { Text("Recording settings") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Divider()

            Text("Behavior", modifier = Modifier.padding(16.dp))

            val autoOpen = settings.autoOpenOnUsbCamera.collectAsState(initial = false)

            Surface(onClick = {
                runBlocking { settings.setAutoOpenOnUsbCamera(!autoOpen.value) }
            }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = autoOpen.value, onCheckedChange = {
                        runBlocking { settings.setAutoOpenOnUsbCamera(it) }
                    })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Auto-open on USB camera")
                        Text(
                            "Open app when a UVC camera is plugged in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val rotateVideo = settings.rotateVideo180.collectAsState(initial = false)

            Surface(onClick = {
                runBlocking { settings.setRotateVideo180(!rotateVideo.value) }
            }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = rotateVideo.value, onCheckedChange = {
                        runBlocking { settings.setRotateVideo180(it) }
                    })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Rotate video 180°")
                        Text(
                            "For upside-down camera mounting",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Divider()

            Text("Overlay", modifier = Modifier.padding(16.dp))

            Surface(onClick = {
                streamingService?.refreshWebViewOverlay()
            }) {
                ListItem(
                    headlineContent = { Text("Refresh Overlay") },
                    supportingContent = { Text("Reload the WebView overlay content") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh overlay"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@ExperimentalMaterial3Api
@Composable
fun RecordingSettingsScreen(
    settings: Settings,
    navigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("Recording Settings")
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
    ) {
        Column(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            Surface(onClick = { /*TODO*/ }) {
                ListItem(
                    headlineContent = { Text("Container") },
                    supportingContent = { Text("MP4") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val maxCapacity = settings.recordingMaxCapacityBytes.collectAsState(initial = 0L)

            Surface(onClick = { /*TODO*/ }) {
                ListItem(
                    headlineContent = { Text("Maximum capacity") },
                    supportingContent = { Text(bytesToString(maxCapacity.value)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
