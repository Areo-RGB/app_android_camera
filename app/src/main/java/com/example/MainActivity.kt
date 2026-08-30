package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.model.AppMode
import com.example.ui.screens.CameraNodeScreen
import com.example.ui.screens.DirectorScreen
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.HubScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StudioDarkBg
import com.example.viewmodel.MultiCamViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MultiCamViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on during filming sessions
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = StudioDarkBg
                ) {
                    MultiCamApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun MultiCamApp(viewModel: MultiCamViewModel) {
    val appMode by viewModel.appMode.collectAsState()
    val takes by viewModel.recordedTakes.collectAsState()

    var showGallery by remember { mutableStateOf(false) }

    // Required Permissions for Video & Audio recording + Local network discovery
    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        list.toTypedArray()
    }

    var hasPermissions by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    AnimatedContent(
        targetState = when {
            showGallery -> "GALLERY"
            appMode == AppMode.DIRECTOR -> "DIRECTOR"
            appMode == AppMode.CAMERA_NODE -> "CAMERA_NODE"
            else -> "HUB"
        },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            "DIRECTOR" -> {
                DirectorScreen(
                    viewModel = viewModel,
                    onBackToHub = { viewModel.selectMode(AppMode.HUB) }
                )
            }
            "CAMERA_NODE" -> {
                CameraNodeScreen(
                    viewModel = viewModel,
                    onBackToHub = { viewModel.selectMode(AppMode.HUB) }
                )
            }
            "GALLERY" -> {
                GalleryScreen(
                    viewModel = viewModel,
                    onBack = { showGallery = false }
                )
            }
            else -> {
                HubScreen(
                    onSelectDirectorMode = {
                        viewModel.selectMode(AppMode.DIRECTOR)
                    },
                    onSelectCameraNodeMode = {
                        viewModel.selectMode(AppMode.CAMERA_NODE)
                    },
                    onOpenGallery = {
                        showGallery = true
                    },
                    savedTakesCount = takes.size
                )
            }
        }
    }
}
