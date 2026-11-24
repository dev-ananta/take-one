package com.takeone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takeone.audio.RecStatus
import com.takeone.audio.Recorder
import com.takeone.ui.EditorScreen
import com.takeone.ui.RecorderScreen
import com.takeone.ui.TakeOneTheme
import com.takeone.ui.Tk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { TakeOneTheme { App() } }
    }
}

@Composable
private fun App(vm: RecorderViewModel = viewModel()) {
    val context = LocalContext.current
    val rec by Recorder.state.collectAsState()
    val snack = remember { SnackbarHostState() }

    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording()
        else vm.message = "TakeOne needs microphone access to record."
    }
    val onRecord = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.startRecording() else launcher.launch(permissions)
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraGranted = ok
        if (!ok) vm.message = "Without camera access TakeOne records sound only."
    }

    // Ask for everything once at launch, so recording can also be started from the Mac.
    val startupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = granted(Manifest.permission.CAMERA)
        if (result[Manifest.permission.RECORD_AUDIO] == false) vm.message = "TakeOne needs microphone access to record."
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filterNot { granted(it) }
        if (wanted.isNotEmpty()) startupLauncher.launch(wanted.toTypedArray())
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }
    LaunchedEffect(rec.hitLimit) {
        if (rec.hitLimit) snack.showSnackbar("Reached the ${Recorder.MAX_MINUTES}-minute limit, so recording stopped.")
    }

    Scaffold(containerColor = Tk.Bg, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (vm.editorOpen && vm.take != null && rec.status == RecStatus.IDLE) EditorScreen(vm)
            else RecorderScreen(
                vm, rec,
                cameraPermission = cameraGranted,
                onRequestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                onRecord = onRecord,
            )
        }
    }
}
