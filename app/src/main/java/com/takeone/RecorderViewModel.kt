package com.takeone

import android.app.Application
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
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
import com.takeone.audio.describeOutputs
import com.takeone.audio.listInputs
import com.takeone.audio.supportsRaw
import com.takeone.video.CameraController
import com.takeone.video.Edl
import com.takeone.video.MemFd
import com.takeone.video.Mp4Exporter
import com.takeone.video.VideoTake
import com.takeone.video.alignAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

enum class ExportFormat(val label: String, val ext: String, val hint: String) {
    MP4("MP4", "mp4", "Video + sound (or sound only) — plays and posts almost anywhere"),
    AAC("AAC", "aac", "Sound only, small file — for voice notes and audio apps"),
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

    // ---- Camera & live view on Mac --------------------------------------------------------
    val camera = CameraController(app)
    /** Video needs Android 11+ (RAM-only files). */
    val videoSupported: Boolean = MemFd.supported
    var videoEnabled by mutableStateOf(MemFd.supported)
    var camLinkCode by mutableStateOf(""); private set
    var camLinkActive by mutableStateOf(false); private set
    var camLinkStatus by mutableStateOf(""); private set
    var camLinkLive by mutableStateOf(false); private set
    val camLinkBridge = CamLinkBridge(
        camera,
        onStatus = { text, live -> camLinkStatus = text; camLinkLive = live },
        onMessage = { message = it },
    )
    val camLinkUrl: String get() = "$CAMLINK_URL/?cam=$camLinkCode&src=app"

    fun startCamLink(code: String) {
        camLinkCode = code.uppercase().filter { it.isLetterOrDigit() }.take(6)
        if (camLinkCode.length != 6) { message = "Enter the 6-character code shown on your Mac."; return }
        camLinkStatus = "Connecting…"
        camLinkLive = false
        camLinkActive = true
        camera.streaming = true
    }

    fun stopCamLink() {
        camLinkActive = false
        camLinkLive = false
        camLinkStatus = ""
        camera.streaming = false
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
    private val undo = ArrayDeque<Pair<ShortArray, Edl?>>()
    var canUndo by mutableStateOf(false); private set

    // Video side of the take (null for audio-only takes)
    var video by mutableStateOf<VideoTake?>(null); private set
    var edl by mutableStateOf<Edl?>(null); private set
    var previewFrame by mutableStateOf<Bitmap?>(null); private set
    /** Fine-tunes lip sync on export: positive delays the sound. */
    var syncMs by mutableIntStateOf(0)
    private var exportFile: MemFd? = null

    // Frame grabbing for the editor preview runs on its own single thread.
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val frameDispatcher = frameExecutor.asCoroutineDispatcher()
    private val frameRequests = MutableStateFlow(-1L)

    // Pairing the microphone take with the camera recording
    private var pendingAudio: Take? = null
    private var awaitingVideo = false
    private var finishedVideo: CameraController.Finished? = null

    init {
        am.registerAudioDeviceCallback(deviceCallback, main)
        refreshDevices()
        viewModelScope.launch {
            Recorder.finished.collect { t ->
                if (t != null) {
                    Recorder.consumeFinished()
                    if (camera.isRecording) camera.stopRecording() // mic stopped first (limit or error)
                    pendingAudio = t
                    tryLoad()
                }
            }
        }
        viewModelScope.launch(frameDispatcher) {
            frameRequests.collect { t ->
                val vt = video ?: return@collect
                val e = edl ?: return@collect
                if (t < 0) return@collect
                val (_, src) = e.toSource(t.coerceIn(0, e.durationUs))
                val bmp = vt.frameAt(src, exact = !player.isPlaying)
                if (bmp != null) withContext(Dispatchers.Main) { previewFrame = bmp }
            }
        }
    }

    fun refreshDevices() {
        val list = listInputs(am)
        inputs = list
        outputs = describeOutputs(am)
        val current = list.firstOrNull { it.id == selectedInputId }
        if (current == null || (!userPicked && list.firstOrNull()?.id != current.id)) {
            selectedInputId = list.firstOrNull()?.id
            if (current == null) userPicked = false
        }
    }

    fun selectInput(id: Int) { selectedInputId = id; userPicked = true }

    val selectedInput: InputDevice? get() = inputs.firstOrNull { it.id == selectedInputId }

    // ---- Recording ------------------------------------------------------------------------

    @SuppressLint("NewApi") // video paths only run when videoSupported (Android 11+)
    fun startRecording() {
        val ctx = getApplication<Application>()
        if (take != null) discard()
        pendingAudio = null
        finishedVideo = null
        awaitingVideo = false

        val ok = Recorder.start(ctx, selectedInput, mode)
        if (!ok) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java))
        } catch (e: Exception) {
            message = "Recording works, but only while the app stays open on screen."
        }
        if (videoEnabled && videoSupported) {
            if (camera.bound && camera.startRecording { onVideoFinished(it) }) {
                awaitingVideo = true
            } else {
                message = "Camera isn't ready — recording sound only."
            }
        }
    }

    fun pause() { Recorder.pause(); camera.pause() }
    fun resume() { Recorder.resume(); camera.resume() }
    fun stopRecording() { Recorder.stop(); camera.stopRecording() }

    private fun onVideoFinished(f: CameraController.Finished) {
        if (Recorder.isRunning) Recorder.stop() // video hit its limit first
        finishedVideo = f
        awaitingVideo = false
        tryLoad()
    }

    private fun tryLoad() {
        val audio = pendingAudio ?: return
        if (awaitingVideo) return
        val v = finishedVideo
        pendingAudio = null
        finishedVideo = null
        viewModelScope.launch { load(audio, v) }
    }

    @SuppressLint("NewApi") // video paths only run when videoSupported (Android 11+)
    private suspend fun load(audio: Take, v: CameraController.Finished?) {
        player.stop()
        var vt: VideoTake? = null
        if (v != null) {
            if (v.error != null) {
                message = v.error + " — kept the sound."
                v.file.close()
            } else {
                vt = withContext(Dispatchers.IO) { VideoTake.probe(v.file) }
                if (vt == null) { message = "The video couldn't be read — kept the sound."; v.file.close() }
            }
        }
        if (vt == null && audio.samples.isEmpty()) return

        sampleRate = audio.sampleRate
        undo.clear(); canUndo = false
        syncMs = 0
        title = "Take " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d h.mm a"))
        previewFrame = null
        video = vt
        if (vt != null) {
            val offsetNs = if (audio.startedAtBootNs > 0 && v!!.startedAtBootNs > 0) audio.startedAtBootNs - v.startedAtBootNs else 0L
            val aligned = withContext(Dispatchers.Default) {
                alignAudio(audio.samples, audio.sampleRate, offsetNs, vt.durationUs)
            }
            edl = Edl.whole(vt.durationUs, vt.keyframesUs)
            setSamples(aligned, resetSelection = true)
        } else {
            edl = null
            setSamples(audio.samples, resetSelection = true)
        }
        requestFrame(0)
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

    private fun us(sample: Int): Long = sample.toLong() * 1_000_000L / sampleRate
    private fun samplesAt(us: Long): Int = (us * sampleRate / 1_000_000L).toInt()
    private fun requestFrame(sample: Int) { if (video != null) frameRequests.value = us(sample) }

    // ---- Selection & playback -------------------------------------------------------------

    fun setSelection(start: Int, end: Int) {
        val n = take?.size ?: return
        val minGap = sampleRate / 10
        val a = start.coerceIn(0, n)
        val b = end.coerceIn(0, n)
        if (b - a >= minGap) {
            val movedStart = a != selStart
            selStart = a; selEnd = b
            requestFrame(if (movedStart) a else b)
        }
    }

    fun selectAll() { take?.let { selStart = 0; selEnd = it.size; requestFrame(0) } }

    val isPlaying: Boolean get() = playhead != null

    fun togglePlay() {
        val s = take ?: return
        if (player.isPlaying) { player.stop(); playhead = null; requestFrame(selStart); return }
        player.play(s, sampleRate, selStart, selEnd) { main.post { playhead = null } }
        playhead = selStart
        viewModelScope.launch {
            delay(20)
            while (player.isPlaying) {
                playhead = player.position
                requestFrame(player.position)
                delay(30)
            }
            playhead = null
        }
    }

    // ---- Edits ----------------------------------------------------------------------------

    private fun edit(label: String, resetSelection: Boolean, op: (ShortArray) -> Pair<ShortArray, Edl?>) {
        val s = take ?: return
        if (busy != null) return
        player.stop(); playhead = null
        busy = label
        val beforeEdl = edl
        viewModelScope.launch {
            val (result, newEdl) = withContext(Dispatchers.Default) { op(s) }
            val depth = if (s.size < sampleRate * 60 * 4) 5 else 1
            undo.addLast(s to beforeEdl)
            while (undo.size > depth) undo.removeFirst()
            canUndo = true
            edl = newEdl
            setSamples(result, resetSelection)
            requestFrame(selStart)
            busy = null
        }
    }

    private fun audioOnly(op: (ShortArray) -> ShortArray): (ShortArray) -> Pair<ShortArray, Edl?> {
        val e = edl
        return { s -> op(s) to e }
    }

    private val fadeLen get() = (sampleRate * 1.5f).toInt()

    fun trimToSelection() {
        val a = selStart; val b = selEnd
        val e = edl
        if (e == null) { edit("Trimming", true, audioOnly { AudioEdits.trim(it, a, b) }); return }
        val r = e.trim(us(a), us(b)) ?: return
        edit("Trimming", true) { AudioEdits.trim(it, samplesAt(r.startUs), samplesAt(r.endUs)) to r.edl }
        if (samplesAt(r.startUs) != a) message = "Start moved back slightly to the nearest video keyframe."
    }

    fun deleteSelection() {
        val s = take ?: return
        val a = selStart; val b = selEnd
        if (a == 0 && b == s.size) { message = "Select part of the recording to cut it."; return }
        val e = edl
        if (e == null) { edit("Cutting", true, audioOnly { AudioEdits.delete(it, a, b) }); return }
        val r = e.cut(us(a), us(b))
        if (r == null) { message = "That would cut everything — select a smaller part."; return }
        edit("Cutting", true) { AudioEdits.delete(it, samplesAt(r.startUs), samplesAt(r.endUs)) to r.edl }
        if (samplesAt(r.endUs) != b) message = "Cut extended slightly to the next video keyframe."
    }

    fun normalize() { val a = selStart; val b = selEnd; edit("Normalizing", false, audioOnly { AudioEdits.normalize(it, a, b) }) }
    fun gain(db: Float) { val a = selStart; val b = selEnd; edit("Adjusting volume", false, audioOnly { AudioEdits.gainDb(it, a, b, db) }) }
    fun fadeIn() { val a = selStart; val b = selEnd; val l = fadeLen; edit("Fading in", false, audioOnly { AudioEdits.fadeIn(it, a, b, l) }) }
    fun fadeOut() { val a = selStart; val b = selEnd; val l = fadeLen; edit("Fading out", false, audioOnly { AudioEdits.fadeOut(it, a, b, l) }) }

    fun undo() {
        if (busy != null || undo.isEmpty()) return
        player.stop(); playhead = null
        val (s, e) = undo.removeLast()
        edl = e
        setSamples(s, resetSelection = true)
        canUndo = undo.isNotEmpty()
        requestFrame(0)
    }

    @SuppressLint("NewApi") // video paths only run when videoSupported (Android 11+)
    fun discard() {
        player.stop(); playhead = null
        take = null
        envelope = FloatArray(0)
        undo.clear(); canUndo = false
        previewFrame = null
        edl = null
        val vt = video
        video = null
        if (vt != null) frameExecutor.execute { vt.release() } // after any frame grab in progress
        exportFile?.close(); exportFile = null
        MemoryAudioProvider.clear()
    }

    // ---- Posting --------------------------------------------------------------------------

    @SuppressLint("NewApi") // video paths only run when videoSupported (Android 11+)
    fun export(format: ExportFormat, onReady: (Intent) -> Unit) {
        val s = take ?: return
        if (busy != null) return
        if (format == ExportFormat.MP4 && !videoSupported) {
            message = "MP4 needs Android 11 or newer on this phone — use AAC."
            return
        }
        player.stop(); playhead = null
        busy = "Preparing ${format.label}"
        progress = 0f
        val ctx = getApplication<Application>()
        val name = sanitize(title).ifBlank { "Take" } + "." + format.ext
        val vt = video
        val e = edl
        val sync = syncMs
        val rate = sampleRate
        viewModelScope.launch {
            try {
                val uri = when (format) {
                    ExportFormat.AAC -> {
                        val bytes = withContext(Dispatchers.Default) { AacEncoder.encode(s, rate) { p -> progress = p } }
                        MemoryAudioProvider.publish(ctx, bytes, "audio/aac", name)
                    }
                    ExportFormat.MP4 -> {
                        val file = withContext(Dispatchers.Default) {
                            val audio = if (vt != null && e != null) alignAudio(s, rate, sync * 1_000_000L, e.durationUs) else s
                            val track = AacEncoder.encodeTrack(audio, rate) { p -> progress = p * 0.4f }
                            Mp4Exporter.export(vt, e, track) { p -> progress = 0.4f + p * 0.6f }
                        }
                        exportFile?.close()
                        exportFile = file
                        MemoryAudioProvider.publish(ctx, file, if (vt != null) "video/mp4" else "audio/mp4", name)
                    }
                }
                val mime = ctx.contentResolver.getType(uri) ?: "*/*"
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, name)
                    clipData = ClipData.newRawUri(name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onReady(Intent.createChooser(send, "Post \"$name\"").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (ex: Exception) {
                message = "Couldn't prepare the file: ${ex.message}"
            } finally {
                busy = null
            }
        }
    }

    private fun sanitize(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)

    @SuppressLint("NewApi") // video paths only run when videoSupported (Android 11+)
    override fun onCleared() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        player.stop()
        camera.release()
        val vt = video
        if (vt != null) frameExecutor.execute { vt.release() }
        frameExecutor.shutdown()
        exportFile?.close()
        super.onCleared()
    }
}
