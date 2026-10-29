package com.kevmo314.kineticstreamer

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class OutputType(
    val route: String,
    val title: String,
    val description: String,
    private val schemes: List<String>,
) {
    WHIP("whip", "WHIP", "WebRTC-HTTP ingestion protocol", listOf("whip://", "https://", "http://")),
    SRT("srt", "SRT", "Secure Reliable Transport", listOf("srt://")),
    RIST("rist", "RIST", "Reliable Internet Stream Transport", listOf("rist://")),
    RTMP("rtmp", "RTMP", "Real-Time Messaging Protocol", listOf("rtmp://")),
    RTSP("rtsp", "RTSP Server", "Start a local RTSP server", listOf("rtsp://")),
    DISK("disk", "Disk", "Save to local storage", listOf("file://"));

    companion object {
        fun fromRoute(route: String?): OutputType? = entries.firstOrNull { it.route == route }

        fun fromUrl(url: String): OutputType? =
            entries.firstOrNull { type -> type.schemes.any { url.startsWith(it) } }
    }
}

/** Splits a stored `whip://endpoint?token=...` url into (endpoint, token), keeping any other query params. */
internal fun parseWhipUrl(url: String): Pair<String, String> {
    val uri = url.removePrefix("whip://").toUri()
    val token = uri.getQueryParameter("token") ?: ""
    val endpoint = uri.buildUpon().clearQuery().apply {
        uri.queryParameterNames.filter { it != "token" }.forEach { appendQueryParameter(it, uri.getQueryParameter(it)) }
    }.build().toString()
    return endpoint to token
}

internal fun buildWhipUrl(endpoint: String, token: String): String = when {
    token.isBlank() -> "whip://$endpoint"
    "?" in endpoint -> "whip://$endpoint&token=${Uri.encode(token)}"
    else -> "whip://$endpoint?token=${Uri.encode(token)}"
}

/** Returns (host, port, query param [key]) for `scheme://host:port?key=value`. */
private fun parseHostPortParam(url: String, key: String, defaultPort: String): Triple<String, String, String> {
    val uri = url.toUri()
    return Triple(
        uri.host ?: "",
        uri.port.takeIf { it > 0 }?.toString() ?: defaultPort,
        uri.getQueryParameter(key) ?: "",
    )
}

/** `rtsp://localhost:<port><path>` -> (port, path) */
internal fun parseRtspUrl(url: String): Pair<String, String> {
    val rest = url.removePrefix("rtsp://").substringAfter(":", "")
    return rest.substringBefore("/") to (if ("/" in rest) "/" + rest.substringAfter("/") else "")
}

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
            for (type in OutputType.entries) {
                Surface(onClick = { navigateTo("settings/output/${type.route}") }) {
                    ListItem(
                        headlineContent = { Text(type.title) },
                        supportingContent = { Text(type.description) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Shared chrome for the per-protocol forms. [buildUrl] returns null while the form is invalid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputForm(
    title: String,
    isEditing: Boolean,
    buildUrl: () -> String?,
    onSave: (OutputConfiguration) -> Unit,
    onDelete: (() -> Unit)?,
    navigateBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val url = buildUrl()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = { Text(if (isEditing) "Edit $title" else title) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditing && onDelete != null) {
                        IconButton(onClick = {
                            onDelete()
                            navigateBack()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    IconButton(
                        onClick = {
                            if (url != null) {
                                onSave(OutputConfiguration(url = url, enabled = true))
                                navigateBack()
                            }
                        },
                        enabled = url != null
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun OutputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/** In the forms below, [initial] is the output being edited, or null when adding a new one. */
@Composable
fun AddWhipOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initial: OutputConfiguration? = null,
) {
    val (initialUrl, initialToken) = remember { initial?.let { parseWhipUrl(it.url) } ?: ("" to "") }
    val (url, setUrl) = remember { mutableStateOf(initialUrl) }
    val (token, setToken) = remember { mutableStateOf(initialToken) }

    OutputForm(
        title = "WHIP Output",
        isEditing = initial != null,
        buildUrl = { if (url.isBlank()) null else buildWhipUrl(url, token) },
        onSave = onSave, onDelete = onDelete, navigateBack = navigateBack,
    ) {
        OutputField(url, setUrl, "WHIP Endpoint URL", "https://example.com/whip/stream", KeyboardType.Uri)
        OutputField(token, setToken, "Bearer Token (optional)", keyboardType = KeyboardType.Password, secret = true)
    }
}

@Composable
fun AddSrtOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initial: OutputConfiguration? = null,
) {
    val (initialHost, initialPort, initialStreamId) =
        remember { initial?.let { parseHostPortParam(it.url, "streamid", "9000") } ?: Triple("", "9000", "") }
    val (host, setHost) = remember { mutableStateOf(initialHost) }
    val (port, setPort) = remember { mutableStateOf(initialPort) }
    val (streamId, setStreamId) = remember { mutableStateOf(initialStreamId) }

    OutputForm(
        title = "SRT Output",
        isEditing = initial != null,
        buildUrl = {
            if (host.isBlank()) null
            else "srt://$host:$port" + if (streamId.isNotBlank()) "?streamid=${Uri.encode(streamId)}" else ""
        },
        onSave = onSave, onDelete = onDelete, navigateBack = navigateBack,
    ) {
        OutputField(host, setHost, "Host", "srt.example.com")
        OutputField(port, setPort, "Port", keyboardType = KeyboardType.Number)
        OutputField(streamId, setStreamId, "Stream ID (optional)")
    }
}

@Composable
fun AddRistOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initial: OutputConfiguration? = null,
) {
    val (initialHost, initialPort, initialCname) =
        remember { initial?.let { parseHostPortParam(it.url, "cname", "1968") } ?: Triple("", "1968", "") }
    val (host, setHost) = remember { mutableStateOf(initialHost) }
    val (port, setPort) = remember { mutableStateOf(initialPort) }
    val (cname, setCname) = remember { mutableStateOf(initialCname) }

    OutputForm(
        title = "RIST Output",
        isEditing = initial != null,
        buildUrl = {
            if (host.isBlank()) null
            else "rist://$host:$port" + if (cname.isNotBlank()) "?cname=${Uri.encode(cname)}" else ""
        },
        onSave = onSave, onDelete = onDelete, navigateBack = navigateBack,
    ) {
        OutputField(host, setHost, "Host", "rist.example.com")
        OutputField(port, setPort, "Port", keyboardType = KeyboardType.Number)
        OutputField(cname, setCname, "CNAME (optional)")
    }
}

@Composable
fun AddRtmpOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initial: OutputConfiguration? = null,
) {
    // The stored url is "<url>/<stream key>"; when editing it all shows up in the url field.
    val (url, setUrl) = remember { mutableStateOf(initial?.url ?: "") }
    val (streamKey, setStreamKey) = remember { mutableStateOf("") }

    OutputForm(
        title = "RTMP Output",
        isEditing = initial != null,
        buildUrl = { if (url.isBlank()) null else if (streamKey.isNotBlank()) "$url/$streamKey" else url },
        onSave = onSave, onDelete = onDelete, navigateBack = navigateBack,
    ) {
        OutputField(url, setUrl, "RTMP URL", "rtmp://a.rtmp.youtube.com/live2", KeyboardType.Uri)
        OutputField(streamKey, setStreamKey, "Stream Key", keyboardType = KeyboardType.Password, secret = true)
    }
}

@Composable
fun AddRtspOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initial: OutputConfiguration? = null,
) {
    val (initialPort, initialPath) = remember { initial?.let { parseRtspUrl(it.url) } ?: ("8554" to "/stream") }
    val (port, setPort) = remember { mutableStateOf(initialPort) }
    val (path, setPath) = remember { mutableStateOf(initialPath) }

    OutputForm(
        title = "RTSP Server",
        isEditing = initial != null,
        buildUrl = { "rtsp://localhost:$port$path" },
        onSave = onSave, onDelete = onDelete, navigateBack = navigateBack,
    ) {
        OutputField(port, setPort, "Port", keyboardType = KeyboardType.Number)
        OutputField(path, setPath, "Path")
    }
}

@Composable
fun AddDiskOutputScreen(
    onSave: (OutputConfiguration) -> Unit,
    navigateBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    initial: OutputConfiguration? = null,
) {
    val (filename, setFilename) = remember { mutableStateOf(initial?.url?.removePrefix("file://") ?: "recording") }

    OutputForm(
        title = "Disk Output",
        isEditing = initial != null,
        buildUrl = { "file://$filename" },
        onSave = onSave, onDelete = onDelete, navigateBack = navigateBack,
    ) {
        OutputField(filename, setFilename, "Filename prefix")
        Text(
            text = "Files will be saved to the app's storage directory",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The form for [type]; [initial] is the output being edited, or null when adding one. */
@Composable
fun OutputEditorScreen(
    type: OutputType,
    initial: OutputConfiguration?,
    onSave: (OutputConfiguration) -> Unit,
    onDelete: (() -> Unit)?,
    navigateBack: () -> Unit,
) {
    when (type) {
        OutputType.WHIP -> AddWhipOutputScreen(onSave, navigateBack, onDelete, initial)
        OutputType.SRT -> AddSrtOutputScreen(onSave, navigateBack, onDelete, initial)
        OutputType.RIST -> AddRistOutputScreen(onSave, navigateBack, onDelete, initial)
        OutputType.RTMP -> AddRtmpOutputScreen(onSave, navigateBack, onDelete, initial)
        OutputType.RTSP -> AddRtspOutputScreen(onSave, navigateBack, onDelete, initial)
        OutputType.DISK -> AddDiskOutputScreen(onSave, navigateBack, onDelete, initial)
    }
}

/** Registers the add (`settings/output/{type}`) and edit (`settings/output/{type}/edit/{index}`) routes for every [OutputType]. */
fun NavGraphBuilder.outputRoutes(settings: Settings, scope: CoroutineScope, navigateBack: () -> Unit) {
    composable("settings/output/{type}") { entry ->
        OutputEditorRoute(settings, scope, entry.arguments?.getString("type"), null, navigateBack)
    }
    composable("settings/output/{type}/edit/{index}") { entry ->
        OutputEditorRoute(
            settings, scope,
            entry.arguments?.getString("type"),
            // A malformed index becomes -1, which is treated as a missing output.
            entry.arguments?.getString("index")?.toIntOrNull() ?: -1,
            navigateBack,
        )
    }
}

/**
 * Add ([index] null) or edit form for the output type named by [typeRoute]. Writes go through
 * [scope] (the activity's) so they finish even though saving pops this screen.
 */
@Composable
private fun OutputEditorRoute(
    settings: Settings,
    scope: CoroutineScope,
    typeRoute: String?,
    index: Int?,
    navigateBack: () -> Unit,
) {
    val type = OutputType.fromRoute(typeRoute)
    val outputs by produceState<List<OutputConfiguration>?>(null) { value = settings.outputs.configurations.first() }
    val existing = index?.let { outputs?.getOrNull(it) }
    // Unknown type, or an edit of an output that is gone: nothing to show.
    if (type == null || (index != null && outputs != null && existing == null)) {
        LaunchedEffect(Unit) { navigateBack() }
        return
    }
    if (index == null) {
        OutputEditorScreen(type, null, { scope.launch { settings.outputs.add(it) } }, null, navigateBack)
    } else if (existing != null) {
        OutputEditorScreen(
            type, existing,
            onSave = { new -> scope.launch { settings.outputs.update(index) { new.copy(enabled = it.enabled) } } },
            onDelete = { scope.launch { settings.outputs.remove(index) } },
            navigateBack = navigateBack,
        )
    }
}
