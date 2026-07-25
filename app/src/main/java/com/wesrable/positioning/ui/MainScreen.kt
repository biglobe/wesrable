package com.wesrable.positioning.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wesrable.positioning.MainViewModel
import com.wesrable.positioning.ui.components.BarometerCard
import com.wesrable.positioning.ui.components.BleListCard
import com.wesrable.positioning.ui.components.OrientationCard
import com.wesrable.positioning.ui.components.PermissionCard
import com.wesrable.positioning.ui.components.PositionCard
import com.wesrable.positioning.ui.components.WifiListCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Device Positioning") }) },
    ) { padding ->
        if (!permissionsGranted) {
            PermissionCard(
                modifier = Modifier.padding(padding),
                onRequestPermissions = onRequestPermissions,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OrientationCard(
                    orientation = state.orientation,
                    available = state.orientationAvailable,
                )
            }
            item {
                PositionCard(position = state.position)
            }
            item {
                BarometerCard(
                    reading = state.barometer,
                    available = state.barometerAvailable,
                )
            }
            item {
                WifiListCard(signals = state.wifiSignals, available = state.wifiAvailable)
            }
            item {
                BleListCard(signals = state.bleSignals, available = state.bleAvailable)
            }
        }
    }
}
