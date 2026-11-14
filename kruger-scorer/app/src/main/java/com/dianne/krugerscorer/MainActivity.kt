package com.dianne.krugerscorer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dianne.krugerscorer.ui.screens.HomeScreen
import com.dianne.krugerscorer.ui.screens.ReviewScreen
import com.dianne.krugerscorer.ui.theme.DianneTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val scorerViewModel: ScorerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DianneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DianneApp(viewModel = scorerViewModel)
                }
            }
        }
    }
}

private enum class Screen { Home, Review }

@Composable
private fun DianneApp(viewModel: ScorerViewModel) {
    val state by viewModel.state.collectAsState()
    var screen by remember { mutableStateOf(Screen.Home) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.loadFromUri(uri)
            screen = Screen.Review
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            viewModel.loadFromUri(uri)
            screen = Screen.Review
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCameraUri?.let { cameraLauncher.launch(it) }
        }
    }

    val context = LocalContext.current

    fun launchCamera() {
        val photoFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        pendingCameraUri = uri
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            cameraLauncher.launch(uri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            onCamera = { launchCamera() },
            onGallery = { galleryLauncher.launch("image/*") },
            onSample = {
                viewModel.loadSample()
                screen = Screen.Review
            }
        )
        Screen.Review -> ReviewScreen(
            state = state,
            onBack = {
                viewModel.clear()
                screen = Screen.Home
            },
            onSensitivity = viewModel::setSensitivity,
            onToggle = viewModel::toggleDetection
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text("OK")
                }
            },
            title = { Text("Aviso") },
            text = { Text(message) }
        )
    }
}
