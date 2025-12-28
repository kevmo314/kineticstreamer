package com.kevmo314.kineticstreamer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOutputScreen(
    navigateTo: (String) -> Unit,
    navigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text("Add Output") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Surface(onClick = { navigateTo("settings/output/whip") }) {
                ListItem(
                    headlineContent = { Text("WHIP") },
                    supportingContent = { Text("WebRTC-HTTP ingestion protocol") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(onClick = { navigateTo("settings/output/srt") }) {
                ListItem(
                    headlineContent = { Text("SRT") },
                    supportingContent = { Text("Secure Reliable Transport") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(onClick = { navigateTo("settings/output/rtmp") }) {
                ListItem(
                    headlineContent = { Text("RTMP") },
                    supportingContent = { Text("Real-Time Messaging Protocol") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(onClick = { navigateTo("settings/output/rtsp") }) {
                ListItem(
                    headlineContent = { Text("RTSP Server") },
                    supportingContent = { Text("Start a local RTSP server") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Surface(onClick = { navigateTo("settings/output/disk") }) {
                ListItem(
                    headlineContent = { Text("Disk") },
                    supportingContent = { Text("Save to local storage") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWhipOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
) {
    val (url, setUrl) = remember { mutableStateOf("") }
    val (bearerToken, setBearerToken) = remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text("WHIP Output") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (url.isNotBlank()) {
                                // Format: whip://url?token=bearerToken
                                val fullUrl = if (bearerToken.isNotBlank()) {
                                    "whip://$url?token=$bearerToken"
                                } else {
                                    "whip://$url"
                                }
                                onSave(OutputConfiguration(url = fullUrl, enabled = true))
                                navigateBack()
                            }
                        },
                        enabled = url.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = setUrl,
                label = { Text("WHIP Endpoint URL") },
                placeholder = { Text("https://example.com/whip/stream") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = bearerToken,
                onValueChange = setBearerToken,
                label = { Text("Bearer Token (optional)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSrtOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
) {
    val (host, setHost) = remember { mutableStateOf("") }
    val (port, setPort) = remember { mutableStateOf("9000") }
    val (streamId, setStreamId) = remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text("SRT Output") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (host.isNotBlank()) {
                                val url = if (streamId.isNotBlank()) {
                                    "srt://$host:$port?streamid=$streamId"
                                } else {
                                    "srt://$host:$port"
                                }
                                onSave(OutputConfiguration(url = url, enabled = true))
                                navigateBack()
                            }
                        },
                        enabled = host.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = setHost,
                label = { Text("Host") },
                placeholder = { Text("srt.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = port,
                onValueChange = setPort,
                label = { Text("Port") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = streamId,
                onValueChange = setStreamId,
                label = { Text("Stream ID (optional)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRtmpOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
) {
    val (url, setUrl) = remember { mutableStateOf("") }
    val (streamKey, setStreamKey) = remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text("RTMP Output") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (url.isNotBlank()) {
                                val fullUrl = if (streamKey.isNotBlank()) {
                                    "$url/$streamKey"
                                } else {
                                    url
                                }
                                onSave(OutputConfiguration(url = fullUrl, enabled = true))
                                navigateBack()
                            }
                        },
                        enabled = url.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = setUrl,
                label = { Text("RTMP URL") },
                placeholder = { Text("rtmp://a.rtmp.youtube.com/live2") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = streamKey,
                onValueChange = setStreamKey,
                label = { Text("Stream Key") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRtspOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
) {
    val (port, setPort) = remember { mutableStateOf("8554") }
    val (path, setPath) = remember { mutableStateOf("/stream") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text("RTSP Server") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val url = "rtsp://localhost:$port$path"
                            onSave(OutputConfiguration(url = url, enabled = true))
                            navigateBack()
                        }
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = port,
                onValueChange = setPort,
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = path,
                onValueChange = setPath,
                label = { Text("Path") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                singleLine = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDiskOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
) {
    val (filename, setFilename) = remember { mutableStateOf("recording") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text("Disk Output") },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val url = "file://$filename"
                            onSave(OutputConfiguration(url = url, enabled = true))
                            navigateBack()
                        }
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = filename,
                onValueChange = setFilename,
                label = { Text("Filename prefix") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                text = "Files will be saved to the app's storage directory",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
