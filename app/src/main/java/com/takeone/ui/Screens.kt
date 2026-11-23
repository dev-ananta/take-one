package com.takeone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takeone.ExportFormat
import com.takeone.RecorderViewModel
import com.takeone.audio.RecState
import com.takeone.audio.RecStatus
import com.takeone.audio.Recorder
import com.takeone.audio.SourceMode
import com.takeone.audio.formatTime

// ============================================================================================
// Shared bits
// ============================================================================================

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Tk.Card, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun MemoryBadge() {
    Row(
        Modifier
            .background(Tk.Accent.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Tk.Accent, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("In memory only · nothing saved to your phone", color = Tk.Accent, fontSize = 12.sp)
    }
}

/** Big round transport button with a hand-drawn glyph. */
@Composable
private fun RoundButton(
    size: Int,
    fill: Color,
    onClick: () -> Unit,
    border: Color? = null,
    enabled: Boolean = true,
    glyph: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .background(if (enabled) fill else Tk.CardHigh, CircleShape)
            .then(if (border != null) Modifier.border(3.dp, border, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

@Composable private fun DotGlyph() = Box(Modifier.size(34.dp).background(Tk.Rec, CircleShape))
@Composable private fun StopGlyph() = Box(Modifier.size(26.dp).background(Color.White, RoundedCornerShape(5.dp)))

@Composable
private fun PauseGlyph(color: Color = Color.White) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width * 0.3f
        drawRoundRect(color, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
        drawRoundRect(color, Offset(size.width * 0.58f, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
    }
}

// ============================================================================================
// Recorder
// ============================================================================================

@Composable
fun RecorderScreen(
    vm: RecorderViewModel,
    rec: RecState,
    cameraPermission: Boolean,
    onRequestCamera: () -> Unit,
    onRecord: () -> Unit,
) {
    val active = rec.status != RecStatus.IDLE
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("TakeOne", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        MemoryBadge()

        // ---- Camera & live view on the Mac ------------------------------------------------
        CameraCard(vm, recording = active, hasPermission = cameraPermission, onRequestPermission = onRequestCamera)
        if (vm.videoEnabled && vm.videoSupported && cameraPermission) MacLiveViewCard(vm)

        // ---- Microphone picker ----------------------------------------------------------
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MICROPHONE", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::refreshDevices, enabled = !active) { Icon(Icons.Filled.Refresh, "Refresh devices", tint = Tk.Dim) }
            }
            var open by remember { mutableStateOf(false) }
            val sel = vm.selectedInput
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Tk.CardHigh, RoundedCornerShape(10.dp))
                        .clickable(enabled = !active) { open = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sel?.label ?: "No microphone found", fontWeight = FontWeight.SemiBold)
                        if (!sel?.detail.isNullOrBlank()) Text(sel!!.detail, color = Tk.Dim, fontSize = 13.sp)
                    }
                    Icon(Icons.Filled.ArrowDropDown, null, tint = Tk.Dim)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    vm.inputs.forEach { d ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(d.label, fontWeight = if (d.id == vm.selectedInputId) FontWeight.Bold else FontWeight.Normal)
                                    if (d.detail.isNotBlank()) Text(d.detail, color = Tk.Dim, fontSize = 12.sp)
                                }
                            },
                            onClick = { vm.selectInput(d.id); open = false },
                        )
                    }
                }
            }
            if (sel?.isBluetooth == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bluetooth mics use the phone's call-quality link, which can also switch your " +
                        "Bluetooth headphones into call mode. For best sound use a USB or wired mic.",
                    color = Color(0xFFFFC560), fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("LISTENING ON", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(vm.outputs, fontWeight = FontWeight.SemiBold)
            Text(
                "Audio from other apps keeps playing to your headphones while you record — " +
                    "only the selected mic is captured.",
                color = Tk.Dim, fontSize = 13.sp,
            )
        }

        // ---- Source mode ----------------------------------------------------------------
        Card {
            Text("PROCESSING", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceMode.entries.forEach { m ->
                    val enabled = !active && (m != SourceMode.RAW || vm.rawSupported)
                    val selected = vm.mode == m
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (selected) Tk.Accent else Tk.CardHigh, RoundedCornerShape(10.dp))
                            .selectable(selected = selected, enabled = enabled) { vm.mode = m }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            m.label,
                            color = when { selected -> Color.Black; enabled -> Color.White; else -> Tk.WaveDim },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (vm.mode == SourceMode.RAW && !vm.rawSupported) "Raw isn't supported on this phone." else vm.mode.hint,
                color = Tk.Dim, fontSize = 13.sp,
            )
        }

        // ---- Timer, level & transport ---------------------------------------------------
        Spacer(Modifier.height(8.dp))
        Text(
            formatTime(rec.samples, rec.sampleRate),
            fontSize = 52.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = if (rec.status == RecStatus.PAUSED) Tk.Dim else Color.White,
        )
        Text(
            when (rec.status) {
                RecStatus.RECORDING -> "● Recording from ${rec.deviceLabel}"
                RecStatus.PAUSED -> "Paused"
                RecStatus.IDLE -> "Up to ${Recorder.MAX_MINUTES} minutes per take"
            },
            color = if (rec.status == RecStatus.RECORDING) Tk.Rec else Tk.Dim,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 14.sp,
        )
        LiveWave(
            rec.history,
            if (rec.status == RecStatus.PAUSED) Tk.WaveDim else Tk.Rec,
            Modifier.fillMaxWidth().height(80.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!active) {
                RoundButton(84, Color.Black, onRecord, border = Color.White, enabled = vm.selectedInput != null || vm.inputs.isEmpty()) { DotGlyph() }
            } else {
                RoundButton(64, Tk.CardHigh, { if (rec.status == RecStatus.PAUSED) vm.resume() else vm.pause() }) {
                    if (rec.status == RecStatus.PAUSED) DotGlyph() else PauseGlyph()
                }
                RoundButton(84, Tk.Rec, vm::stopRecording) { StopGlyph() }
            }
        }
        rec.error?.let { Text(it, color = Tk.Rec, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================================================
// Editor
// ============================================================================================

@Composable
fun EditorScreen(vm: RecorderViewModel) {
    val samples = vm.take ?: return
    val rate = vm.sampleRate
    val total = samples.size.coerceAtLeast(1)
    val context = LocalContext.current
    var showPost by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler { confirmDiscard = true }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit take", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(formatTime(samples.size, rate), color = Tk.Dim, fontFamily = FontFamily.Monospace)
        }
        MemoryBadge()
        VideoEditorPanel(vm)

        OutlinedTextField(
            value = vm.title,
            onValueChange = { vm.title = it.take(60) },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Card {
            EditableWaveform(
                envelope = vm.envelope,
                start = vm.selStart.toFloat() / total,
                end = vm.selEnd.toFloat() / total,
                playhead = vm.playhead?.let { it.toFloat() / total },
                onChange = { a, b -> vm.setSelection((a * total).toInt(), (b * total).toInt()) },
                modifier = Modifier.fillMaxWidth().height(170.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${formatTime(vm.selStart, rate)} – ${formatTime(vm.selEnd, rate)}",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("Selected ${formatTime(vm.selEnd - vm.selStart, rate)}", color = Tk.Dim, fontSize = 13.sp)
                }
                TextButton(onClick = vm::selectAll) { Text("Select all") }
            }
            Text(
                if (vm.video != null) "Drag the handles to choose a section. Trims and cuts line up with the video's keyframes (about every second)."
                else "Drag the handles to choose a section. Edits apply to the selection.",
                color = Tk.Dim, fontSize = 12.sp,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RoundButton(72, Tk.Accent, vm::togglePlay, enabled = vm.busy == null) {
                if (vm.isPlaying) PauseGlyph(Color.Black)
                else Icon(Icons.Filled.PlayArrow, "Play selection", tint = Color.Black, modifier = Modifier.size(40.dp))
            }
        }

        val enabled = vm.busy == null
        Card {
            Text("EDIT", color = Tk.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ToolRow {
                Tool("Trim to selection", enabled, vm::trimToSelection)
                Tool("Cut selection", enabled, vm::deleteSelection)
            }
            ToolRow {
                Tool("Fade in", enabled, vm::fadeIn)
                Tool("Fade out", enabled, vm::fadeOut)
                Tool("Normalize", enabled, vm::normalize)
            }
            ToolRow {
                Tool("−3 dB", enabled) { vm.gain(-3f) }
                Tool("+3 dB", enabled) { vm.gain(3f) }
                Tool("Undo", enabled && vm.canUndo, vm::undo)
            }
        }

        SyncControl(vm)

        vm.busy?.let { label ->
            Column {
                Text(label + "…", color = Tk.Dim)
                Spacer(Modifier.height(6.dp))
                if (label.startsWith("Preparing") && vm.progress > 0f) {
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth(), color = Tk.Accent)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Tk.Accent)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { confirmDiscard = true },
                modifier = Modifier.weight(1f).height(52.dp),
                border = BorderStroke(1.dp, Tk.Line),
            ) { Text("Discard", color = Tk.Rec) }
            Button(
                onClick = { showPost = true },
                enabled = enabled,
                modifier = Modifier.weight(1.6f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tk.Accent, contentColor = Color.Black),
            ) { Text("Post…", fontWeight = FontWeight.Bold) }
        }
        Text(
            "Posting hands the file to the app you choose (Messages, WhatsApp, Drive, email…) " +
                "directly from memory. Closing TakeOne discards the take.",
            color = Tk.Dim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showPost) {
        var format by remember { mutableStateOf(if (vm.videoSupported) ExportFormat.MP4 else ExportFormat.AAC) }
        AlertDialog(
            onDismissRequest = { showPost = false },
            title = { Text("Post recording") },
            text = {
                Column {
                    ExportFormat.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = format == f) { format = f }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = format == f, onClick = { format = f })
                            Column {
                                Text(f.label, fontWeight = FontWeight.SemiBold)
                                Text(f.hint, color = Tk.Dim, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val seconds = samples.size.toLong() / rate
                    val est = when (format) {
                        ExportFormat.MP4 -> seconds * (160_000 + if (vm.video != null) com.takeone.video.CameraController.VIDEO_BITRATE else 0) / 8
                        ExportFormat.AAC -> seconds * 160_000 / 8
                    }
                    Text("About ${"%.1f".format(est / 1_048_576.0)} MB", color = Tk.Dim, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPost = false
                    vm.export(format) { intent -> context.startActivity(intent) }
                }) { Text("Choose app") }
            },
            dismissButton = { TextButton(onClick = { showPost = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this take?") },
            text = { Text("It only exists in memory, so it will be gone for good.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.discard() }) { Text("Discard", color = Tk.Rec) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun RowScope.Tool(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        border = BorderStroke(1.dp, Tk.Line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
    ) { Text(label, fontSize = 13.sp, maxLines = 1, color = if (enabled) Color.White else Tk.WaveDim) }
}
