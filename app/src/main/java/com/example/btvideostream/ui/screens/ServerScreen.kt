package com.example.btvideostream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.btvideostream.bluetooth.ConnectionState
import com.example.btvideostream.ui.components.ConnectionIndicator
import com.example.btvideostream.ui.components.ThemeSwitcher
import com.example.btvideostream.ui.server.ServerViewModel
import com.example.btvideostream.ui.theme.ThemeController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    themeController: ThemeController,
    onBack: () -> Unit,
    vm: ServerViewModel = viewModel(),
) {
    val state by vm.connectionState.collectAsState()
    val log by vm.log.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll al final del log
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servidor") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.btManager.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = { ThemeSwitcher(themeController) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionIndicator(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            if (state is ConnectionState.Error || state is ConnectionState.Disconnected) {
                Button(
                    onClick = { vm.startListening() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Esperar conexión de cliente")
                }
            }

            Text("Log de actividad", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(log) { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
