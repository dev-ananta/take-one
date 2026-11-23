package com.takeone

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.takeone.audio.AacEncoder
import com.takeone.audio.AudioEdits
import com.takeone.audio.InputDevice
import com.takeone.audio.Player
import com.takeone.audio.Recorder
import com.takeone.audio.SourceMode
import com.takeone.audio.Take
import com.takeone.audio.WavWriter
import com.takeone.audio.describeOutputs
import com.takeone.audio.listInputs
import com.takeone.audio.supportsRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ExportFormat(val label: String, val ext: String, val mime: String, val hint: String) {
    AAC("AAC", "aac", "audio/aac", "Small file, great quality — best for messaging & social"),
    WAV("WAV", "wav", "audio/wav", "Lossless, large — best for editing elsewhere"),
}

class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    private val am = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val player = Player()

    // ---- Devices --------------------------------------------------------------------------
    var inputs by mutableStateOf<List<InputDevice>>(emptyList()); private set
    var selectedInputId by mutableStateOf<Int?>(null); private set
    var outputs by mutableStateOf(""); private set
    var mode by mutableStateOf(SourceMode.STANDARD)
    val rawSupported: Boolean = supportsRaw(am)
    private var userPicked = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
    }

    // ---- Editor ---------------------------------------------------------------------------
    var take by mutableStateOf<ShortArray?>(null); private set
    var sampleRate by mutableIntStateOf(48000); private set
    var envelope by mutableStateOf(FloatArray(0)); private set
    var selStart by mutableIntStateOf(0); private set
    var selEnd by mutableIntStateOf(0); private set
    var playhead by mutableStateOf<Int?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var title by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    private val undo = ArrayDeque<ShortArray>()
    var canUndo by mutableStateOf(false); private set

    init {
        am.registerAudioDeviceCallback(deviceCallback, main)
        refreshDevices()
        viewModelScope.launch {
            Recorder.finished.collect { t -> if (t != null) Recorder.consumeFinished()?.let { load(it) } }
        }
    }

    fun refreshDevices() {
        val list = listInputs(am)
        inputs = list
        outputs = describeOutputs(am)
        val current = list.firstOrNull { it.id == selectedInputId }
        // Auto-pick the best external mic unless the user chose one that's still connected.
        if (current == null || (!userPicked && list.firstOrNull()?.id != current.id)) {
            selectedInputId = list.firstOrNull()?.id
            if (current == null) userPicked = false
        }
    }

    fun selectInput(id: Int) { selectedInputId = id; userPicked = true }

    val selectedInput: InputDevice? get() = inputs.firstOrNull { it.id == selectedInputId }

    // ---- Recording ------------------------------------------------------------------------

    fun startRecording() {
        val ctx = getApplication<Application>()
        if (take != null) discard()
        val ok = Recorder.start(ctx, selectedInput, mode)
        if (ok) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java))
            } catch (e: Exception) {
                message = "Recording works, but only while the app stays open on screen."
            }
        }
    }

    fun pause() = Recorder.pause()
    fun resume() = Recorder.resume()
    fun stopRecording() = Recorder.stop()

    private fun load(t: Take) {
        player.stop()
        sampleRate = t.sampleRate
        undo.clear(); canUndo = false
        title = "Take " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d h.mm a"))
        setSamples(t.samples, resetSelection = true)
    }

    private fun setSamples(s: ShortArray, resetSelection: Boolean) {
        take = s
        if (resetSelection || selEnd > s.size || selStart >= selEnd) {
            selStart = 0; selEnd = s.size
        }
        viewModelScope.launch {
            envelope = withContext(Dispatchers.Default) { AudioEdits.envelope(s, 1500) }
        }
    }

    // ---- Selection & playback -------------------------------------------------------------

    fun setSelection(start: Int, end: Int) {
        val n = take?.size ?: return
        val minGap = sampleRate / 10
        val a = start.coerceIn(0, n)
        val b = end.coerceIn(0, n)
        if (b - a >= minGap) { selStart = a; selEnd = b }
    }

    fun selectAll() { take?.let { selStart = 0; selEnd = it.size } }

    val isPlaying: Boolean get() = playhead != null

    fun togglePlay() {
        val s = take ?: return
        if (player.isPlaying) { player.stop(); playhead = null; return }
        player.play(s, sampleRate, selStart, selEnd) { main.post { playhead = null } }
        playhead = selStart
        viewModelScope.launch {
            delay(20)
            while (player.isPlaying) { playhead = player.position; delay(30) }
            playhead = null
        }
    }

    // ---- Edits ----------------------------------------------------------------------------

    private fun edit(label: String, resetSelection: Boolean, op: (ShortArray) -> ShortArray) {
        val s = take ?: return
        if (busy != null) return
        player.stop(); playhead = null
        busy = label
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { op(s) }
            // Undo history is memory-hungry; keep fewer steps for long takes.
            val depth = if (s.size < sampleRate * 60 * 4) 5 else 1
            undo.addLast(s)
            while (undo.size > depth) undo.removeFirst()
            canUndo = true
            setSamples(result, resetSelection)
            busy = null
        }
    }

    private val fadeLen get() = (sampleRate * 1.5f).toInt()

    fun trimToSelection() = edit("Trimming", true) { AudioEdits.trim(it, selStart, selEnd) }
    fun deleteSelection() {
        val s = take ?: return
        if (selStart == 0 && selEnd == s.size) { message = "Select part of the recording to delete it."; return }
        edit("Deleting", true) { AudioEdits.delete(it, selStart, selEnd) }
    }
    fun normalize() = edit("Normalizing", false) { AudioEdits.normalize(it, selStart, selEnd) }
    fun gain(db: Float) = edit("Adjusting volume", false) { AudioEdits.gainDb(it, selStart, selEnd, db) }
    fun fadeIn() = edit("Fading in", false) { AudioEdits.fadeIn(it, selStart, selEnd, fadeLen) }
    fun fadeOut() = edit("Fading out", false) { AudioEdits.fadeOut(it, selStart, selEnd, fadeLen) }

    fun undo() {
        if (busy != null || undo.isEmpty()) return
        player.stop(); playhead = null
        setSamples(undo.removeLast(), resetSelection = false)
        canUndo = undo.isNotEmpty()
    }

    fun discard() {
        player.stop(); playhead = null
        take = null
        envelope = FloatArray(0)
        undo.clear(); canUndo = false
        MemoryAudioProvider.clear()
    }

    // ---- Posting --------------------------------------------------------------------------

    fun export(format: ExportFormat, onReady: (Intent) -> Unit) {
        val s = take ?: return
        if (busy != null) return
        player.stop(); playhead = null
        busy = "Preparing ${format.label}"
        progress = 0f
        val ctx = getApplication<Application>()
        val name = sanitize(title).ifBlank { "Take" } + "." + format.ext
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    when (format) {
                        ExportFormat.WAV -> WavWriter.encode(s, sampleRate)
                        ExportFormat.AAC -> AacEncoder.encode(s, sampleRate) { p -> progress = p }
                    }
                }
                val uri = MemoryAudioProvider.publish(ctx, bytes, format.mime, name)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = format.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, name)
                    clipData = ClipData.newRawUri(name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onReady(Intent.createChooser(send, "Post \"$name\"").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                message = "Couldn't prepare the file: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    private fun sanitize(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)

    override fun onCleared() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        player.stop()
        super.onCleared()
    }
}
