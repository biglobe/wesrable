package com.wesrable.positioning.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun PermissionCard(modifier: Modifier = Modifier, onRequestPermissions: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "This app estimates device position from WiFi and Bluetooth signal " +
                "scans, plus onboard motion sensors — it never connects to a " +
                "network or pairs with a device. Android requires location " +
                "permission to read scan results, and Bluetooth-scan permission " +
                "to observe BLE advertisements.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant permissions")
        }
    }
}
