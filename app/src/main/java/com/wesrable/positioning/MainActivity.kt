package com.wesrable.positioning

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.wesrable.positioning.ui.MainScreen
import com.wesrable.positioning.ui.theme.DevicePositioningTheme

/** All permissions this app can use for RF-signal-based positioning; none of
 * them grant network access, only the ability to read local scan results. */
private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }

            val launcher = rememberActivityResultLauncher { granted ->
                permissionsGranted = granted.values.all { it }
            }

            DevicePositioningTheme {
                MainScreen(
                    viewModel = viewModel,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { launcher.launch(requiredPermissions()) },
                )
            }

            DisposableEffect(permissionsGranted) {
                if (permissionsGranted) viewModel.startSensing()
                onDispose { viewModel.stopSensing() }
            }
        }
    }
}

@Composable
private fun rememberActivityResultLauncher(
    onResult: (Map<String, Boolean>) -> Unit,
) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
    onResult = onResult,
)
