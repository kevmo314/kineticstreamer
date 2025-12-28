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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Text("Outputs", modifier = Modifier.weight(1f))
                IconButton(onClick = { navigateTo("settings/output/add") }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add output"
                    )
                }
            }

            val outputConfigurations = settings.outputConfigurations.collectAsState(initial = emptyList())

            for ((index, outputConfiguration) in outputConfigurations.value.withIndex()) {
                Surface(onClick = {
                    runBlocking {
                        val updatedConfigurations = outputConfigurations.value.toMutableList()
                        updatedConfigurations[index] = outputConfiguration.withEnabled(!outputConfiguration.enabled)
                        settings.setOutputConfigurations(updatedConfigurations)
                    }
                }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = outputConfiguration.enabled, onCheckedChange = {
                            runBlocking {
                                val updatedConfigurations = outputConfigurations.value.toMutableList()
                                updatedConfigurations[index] = outputConfiguration.withEnabled(!outputConfiguration.enabled)
                                settings.setOutputConfigurations(updatedConfigurations)
                            }
                        })
                        Column {
                            Text(outputConfiguration.protocol)
                            Text(outputConfiguration.displayName)
                        }
                    }
                }
            }

            HorizontalDivider()

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
