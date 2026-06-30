package com.example.btvideostream.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicVideo
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.btvideostream.bluetooth.ConnectionState
import com.example.btvideostream.server.VideoResult
import com.example.btvideostream.ui.client.ClientViewModel
import com.example.btvideostream.ui.components.ConnectionIndicator
import com.example.btvideostream.ui.components.ThemeSwitcher
import com.example.btvideostream.ui.theme.ThemeController
import java.io.File

enum class SearchSource(val label: String, val hint: String, val code: String, val icon: ImageVector) {
    YOUTUBE("YouTube", "Buscar en YouTube...", "yt", Icons.Filled.PlayCircle),
    TIKTOK("TikTok", "Buscar en TikTok...", "tt", Icons.Filled.MusicVideo),
    BROWSER("Navegador Google", "Buscar en Google...", "web", Icons.Filled.Language),
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    themeController: ThemeController,
    onBack: () -> Unit,
    vm: ClientViewModel = viewModel(),
) {
    val state by vm.connectionState.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val isReceiving by vm.isReceivingVideo.collectAsState()
    val videoFile by vm.videoFile.collectAsState()
    val errorMsg by vm.errorMsg.collectAsState()

    val snackbarState = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<SearchSource?>(null) }

    LaunchedEffect(errorMsg) {
        errorMsg?.let { snackbarState.showSnackbar(it); vm.clearError() }
    }

    // Reiniciar fuente si se desconecta
    LaunchedEffect(state) {
        if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
            selectedSource = null
            query = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectedSource != null) selectedSource!!.label else "Cliente")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedSource != null) {
                            selectedSource = null
                            query = ""
                            vm.clearSearch()
                        } else { vm.btManager.disconnect(); onBack() }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },

                actions = { ThemeSwitcher(themeController) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ConnectionIndicator(
                state = state,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            when (state) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> {
                    if (state is ConnectionState.Error) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Conexión perdida. Selecciona el servidor para reconectar.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    DevicePicker(vm.pairedDevices) { vm.connectTo(it) }
                }

                is ConnectionState.Connecting -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Conectando…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is ConnectionState.Connected -> {
                    // Reproductor si hay video listo
                    videoFile?.let { file ->
                        VideoPlayer(file = file, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                    }

                    if (selectedSource == null) {
                        // ── Selector de fuente ──
                        SourceSelector { src -> selectedSource = src; query = "" }
                    } else {
                        // ── Barra de búsqueda ──
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(selectedSource!!.hint) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (query.isNotBlank()) vm.search(query, selectedSource!!.code)
                                }),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        if (query.isNotBlank()) vm.search(query, selectedSource!!.code)
                                    }) {
                                        Icon(Icons.Filled.Search, contentDescription = "Buscar")
                                    }
                                }
                            )
                        }

                        when {
                            isSearching || isReceiving -> {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            if (isReceiving) "Recibiendo video por Bluetooth…"
                                            else "Buscando en ${selectedSource!!.label}…",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }

                            searchResults.isNotEmpty() -> {
                                val isWeb = selectedSource?.code == "web"
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(searchResults) { result ->
                                        SearchResultItem(
                                            result = result,
                                            isWebResult = isWeb,
                                            onClick = {
                                                if (isWeb) {
                                                    // thumbnailUrl contiene la URL real del resultado
                                                    val url = result.thumbnailUrl.ifEmpty {
                                                        "https://duckduckgo.com/?q=${result.title}"
                                                    }
                                                    vm.requestOpenUrl(url)
                                                } else {
                                                    vm.requestVideo(result.videoId)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSelector(onSelect: (SearchSource) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Selecciona una fuente",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        SearchSource.entries.forEach { source ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(source) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        source.icon, contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            source.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            source.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DevicePicker(devices: List<BluetoothDevice>, onConnect: (BluetoothDevice) -> Unit) {
    Text("Dispositivos emparejados", style = MaterialTheme.typography.labelLarge)
    if (devices.isEmpty()) {
        Text(
            "No hay dispositivos emparejados. Ve a Ajustes > Bluetooth y vincula el Servidor.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
        )
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name ?: "Desconocido", style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onConnect(device) }) { Text("Conectar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(result: VideoResult, isWebResult: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (isWebResult) {
                Text(
                    result.channel, // dominio
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (!isWebResult && result.channel.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(result.channel)
                        if (result.duration.isNotEmpty()) append(" • ${result.duration}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (isWebResult && result.duration.isNotEmpty()) {
                // duration se usa como snippet en resultados web
                Spacer(Modifier.height(4.dp))
                Text(
                    result.duration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Toca para abrir en el navegador del Servidor",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(file.absolutePath) {
        exoPlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        modifier = modifier.aspectRatio(16f / 9f)
    )
}
