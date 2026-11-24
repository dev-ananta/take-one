package com.takeone.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takeone.RecorderViewModel
import com.takeone.video.CameraController

/** Camera on/off, on-screen preview and flip. Shown on the recorder screen. */
@Composable
fun CameraCard(vm: RecorderViewModel, recording: Boolean, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val cam = vm.camera
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CAMERA", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (vm.videoEnabled) "Recording video + sound" else "Sound only",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Switch(
                checked = vm.videoEnabled,
                onCheckedChange = { vm.videoEnabled = it },
                enabled = !recording,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Tk.Accent),
            )
        }
        if (vm.videoEnabled) {
            Spacer(Modifier.height(10.dp))
            if (!hasPermission) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Tk.Accent, contentColor = Color.Black),
                ) { Text("Allow camera") }
            } else {
                CameraPreview(cam)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (cam.facingBack) "Back camera" else "Front camera") +
                            " · saved to Movies/TakeOne",
                        color = Tk.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { cam.flip() }, enabled = !recording && cam.bound) { Text("Flip") }
                }
                cam.error?.let { Text(it, color = Tk.Rec, fontSize = 13.sp) }
                // Keep the screen awake: Android stops the camera if the phone locks.
                val view = LocalView.current
                DisposableEffect(Unit) {
                    view.keepScreenOn = true
                    onDispose { view.keepScreenOn = false }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(cam: CameraController) {
    val owner = LocalLifecycleOwner.current
    // The preview has the same shape as the video (16:9, upright when the phone is upright),
    // so what you see here — and on the Mac — is exactly what gets recorded.
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // TextureView mode lets the Mac live view copy frames from the preview.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    cam.bind(owner, this)
                }
            },
            modifier = Modifier
                .fillMaxWidth(if (portrait) 0.62f else 1f)
                .aspectRatio(if (portrait) 9f / 16f else 16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        )
    }
    DisposableEffect(Unit) { onDispose { cam.unbind() } }
}

/** Streams the camera to the CamLink page on a Mac (enter the code shown there). */
@Composable
fun MacLiveViewCard(vm: RecorderViewModel) {
    SectionCard {
        Text("LIVE VIEW ON MAC", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (!vm.camLinkActive) {
            Text(
                "On your Mac, open camlink-xi.vercel.app and choose \"I'm on the Mac\". Enter the code it shows:",
                color = Tk.Dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            var code by rememberSaveable { mutableStateOf(vm.camLinkCode) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    placeholder = { Text("ABC123") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, letterSpacing = 4.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.startCamLink(code) },
                    enabled = code.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = Tk.Accent, contentColor = Color.Black),
                ) { Text("Connect") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (vm.camLinkLive) Tk.Rec else Color(0xFFFFC560), CircleShape))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.camLinkStatus.ifBlank { "Connecting…" }, fontWeight = FontWeight.SemiBold)
                    Text("Code ${vm.camLinkCode}", color = Tk.Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = vm::stopCamLink) { Text("Disconnect") }
            }
            Text(
                "Use Record on the Mac to start and stop TakeOne — it records with the mic selected above.",
                color = Tk.Dim, fontSize = 12.sp,
            )
            // The CamLink page does the streaming; it only needs to exist, so it's kept tiny.
            CamLinkWebView(vm, Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun CamLinkWebView(vm: RecorderViewModel, modifier: Modifier) {
    val url = vm.camLinkUrl
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(vm.camLinkBridge, "TakeOne")
                loadUrl(url)
            }
        },
        modifier = modifier,
        onRelease = { it.destroy() },
    )
}

/** Video frame + lip-sync control for the editor. */
@Composable
fun VideoEditorPanel(vm: RecorderViewModel) {
    val frame = vm.previewFrame
    val vt = vm.video ?: return
    val aspect = if (vt.rotation % 180 == 0) vt.width.toFloat() / vt.height else vt.height.toFloat() / vt.width
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.5f, 2f))
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(frame.asImageBitmap(), contentDescription = "Video frame", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        } else {
            Text("Loading video…", color = Tk.Dim)
        }
    }
}

@Composable
fun SyncControl(vm: RecorderViewModel) {
    if (vm.video == null) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIP SYNC", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                when {
                    vm.syncMs == 0 -> "Auto"
                    vm.syncMs > 0 -> "Sound ${vm.syncMs} ms later"
                    else -> "Sound ${-vm.syncMs} ms earlier"
                },
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            if (vm.syncMs != 0) TextButton(onClick = { vm.syncMs = 0 }) { Text("Reset") }
        }
        Slider(
            value = vm.syncMs.toFloat(),
            onValueChange = { vm.syncMs = (it / 10f).toInt() * 10 },
            valueRange = -300f..300f,
            colors = SliderDefaults.colors(thumbColor = Tk.Accent, activeTrackColor = Tk.Accent, inactiveTrackColor = Tk.CardHigh),
        )
        Text(
            "Sound and picture are lined up automatically. If lips look early or late in the posted video, nudge this.",
            color = Tk.Dim, fontSize = 12.sp,
        )
    }
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tk.Card, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) { content() }
}

/** Shows saving progress, then the last saved take with Edit / Share. */
@Composable
fun LastTakeCard(vm: RecorderViewModel, onShare: () -> Unit) {
    val saving = vm.saving
    val last = vm.lastSaved
    if (saving == null && last == null) return
    SectionCard {
        if (saving != null) {
            Text("SAVING TO PHONE", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { vm.saveProgress }, modifier = Modifier.fillMaxWidth(), color = Tk.Accent,
            )
            Spacer(Modifier.height(6.dp))
            Text("${(vm.saveProgress * 100).toInt()}% — adding your mic's sound to the video", color = Tk.Dim, fontSize = 13.sp)
        } else if (last != null) {
            Text("LAST TAKE — SAVED", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(last.name, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(last.mainFile.folder)
                    if (last.wav != null) append(" · mic WAV in ${last.wav.folder}")
                },
                color = Tk.Dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::openEditor, enabled = vm.take != null) { Text("Edit") }
                Button(
                    onClick = onShare,
                    colors = ButtonDefaults.buttonColors(containerColor = Tk.Accent, contentColor = Color.Black),
                ) { Text("Share…") }
            }
        }
    }
}

@Composable
fun SaveWavSwitch(vm: RecorderViewModel, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Also save the mic as WAV", fontWeight = FontWeight.SemiBold)
            Text("Lossless copy of the sound for editing (Music/TakeOne)", color = Tk.Dim, fontSize = 13.sp)
        }
        Switch(
            checked = vm.saveWav,
            onCheckedChange = { vm.saveWav = it },
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Tk.Accent),
        )
    }
}
