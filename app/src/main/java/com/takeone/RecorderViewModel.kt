package com.takeone

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.takeone.audio.RecStatus
import com.takeone.audio.Recorder
import com.takeone.audio.SourceMode
import com.takeone.audio.Take
import com.takeone.audio.WavWriter
import com.takeone.audio.describeOutputs
import com.takeone.audio.listInputs
import com.takeone.audio.supportsRaw
import com.takeone.video.CameraController
import com.takeone.video.Edl
import com.takeone.video.Mp4Exporter
import com.takeone.video.VideoTake
import com.takeone.video.alignAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** A take that has been saved to the phone. */
class SavedTake(
    val name: String,
    val video: MediaSaver.Saved?,
    val mainFile: MediaSaver.Saved,
    val wav: MediaSaver.Saved?,
)

class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val am = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val player = Player()
    private val takesDir = File(app.cacheDir, "takes").apply { deleteRecursively(); mkdirs() }

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
    var videoEnabled by mutableStateOf(true)
    var saveWav by mutableStateOf(true)
    var camLinkCode by mutableStateOf(""); private set
    var camLinkActive by mutableStateOf(false); private set
    var camLinkStatus by mutableStateOf(""); private set
    var camLinkLive by mutableStateOf(false); private set
    val camLinkBridge = CamLinkBridge(
        capsProvider = { capsJson() },
        onCommand = { handleMacCommand(it) },
        onStatus = { text, live -> camLinkStatus = text; camLinkLive = live },
    )
    val camLinkUrl: String get() = "$CAMLINK_URL/?cam=$camLinkCode&src=app"

    init { camLinkBridge.frameSource = camera }

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

    // ---- Saving ---------------------------------------------------------------------------
    var saving by mutableStateOf<String?>(null); private set
    var saveProgress by mutableFloatStateOf(0f); private set
    var lastSaved by mutableStateOf<SavedTake?>(null); private set

    // ---- Editor ---------------------------------------------------------------------------
    var editorOpen by mutableStateOf(false); private set
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

    var video by mutableStateOf<VideoTake?>(null); private set
    var edl by mutableStateOf<Edl?>(null); private set
    var previewFrame by mutableStateOf<Bitmap?>(null); private set
    /** Fine-tunes lip sync on export: positive delays the sound. */
    var syncMs by mutableIntStateOf(0)

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
                    tryProcess()
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

    private fun micGranted() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Why recording can't start right now, or null if it can. */
    private fun cannotRecordReason(): String? = when {
        !micGranted() -> "Allow microphone access in TakeOne first"
        editorOpen -> "Close the editor on the phone first"
        saving != null -> "Still saving the last take"
        else -> null
    }

    // ---- Recording ------------------------------------------------------------------------

    fun startRecording() {
        cannotRecordReason()?.let { message = it; return }
        if (Recorder.isRunning) return
        releaseTake()
        pendingAudio = null
        finishedVideo = null
        awaitingVideo = false

        if (!Recorder.start(ctx, selectedInput, mode)) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java))
        } catch (e: Exception) {
            message = "Recording works, but only while the app stays open on screen."
        }
        if (videoEnabled) {
            val file = File(takesDir, "camera-${System.currentTimeMillis()}.mp4")
            if (camera.bound && camera.startRecording(file) { onVideoFinished(it) }) {
                awaitingVideo = true
            } else {
                message = camera.error ?: "Camera isn't ready — recording sound only."
            }
        }
    }

    fun pause() { Recorder.pause(); camera.pause() }
    fun resume() { Recorder.resume(); camera.resume() }
    fun stopRecording() { Recorder.stop(); camera.stopRecording() }

    private fun onVideoFinished(f: CameraController.Finished) {
        if (Recorder.isRunning) Recorder.stop()
        finishedVideo = f
        awaitingVideo = false
        tryProcess()
    }

    private fun tryProcess() {
        val audio = pendingAudio ?: return
        if (awaitingVideo) return
        val v = finishedVideo
        pendingAudio = null
        finishedVideo = null
        viewModelScope.launch { processAndSave(audio, v) }
    }

    /** Lines up sound and picture, then saves the take to the phone (MP4, plus WAV of the mic). */
    private suspend fun processAndSave(audio: Take, v: CameraController.Finished?) {
        saving = "Saving"
        saveProgress = 0f
        try {
            var vt: VideoTake? = null
            if (v != null) {
                if (v.error != null) {
                    message = v.error + " — saved the sound only."
                    v.file.delete()
                } else {
                    vt = withContext(Dispatchers.IO) { VideoTake.probe(v.file) }
                    if (vt == null) { message = "The video file couldn't be read — saved the sound only."; v.file.delete() }
                }
            }
            if (vt == null && audio.samples.isEmpty()) { message = "Nothing was recorded."; return }

            val rate = audio.sampleRate
            val samples = if (vt != null) {
                val offsetNs = if (audio.startedAtBootNs > 0 && v!!.startedAtBootNs > 0) audio.startedAtBootNs - v.startedAtBootNs else 0L
                withContext(Dispatchers.Default) { alignAudio(audio.samples, rate, offsetNs, vt.durationUs) }
            } else audio.samples
            val whole = vt?.let { Edl.whole(it.durationUs, it.keyframesUs) }
            val name = "TakeOne " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))

            val saved = withContext(Dispatchers.IO) {
                val track = AacEncoder.encodeTrack(samples, rate) { p -> saveProgress = p * 0.5f }
                val tmp = File(takesDir, "out.mp4")
                Mp4Exporter.export(vt, whole, track, tmp) { p -> saveProgress = 0.5f + p * 0.3f }
                val primary = if (vt != null) MediaSaver.saveVideo(ctx, "$name.mp4", MediaSaver.copyFrom(tmp))
                else MediaSaver.saveAudio(ctx, "$name.mp4", "audio/mp4", MediaSaver.copyFrom(tmp))
                tmp.delete()
                saveProgress = 0.85f
                val wav = if (saveWav && samples.isNotEmpty())
                    MediaSaver.saveAudio(ctx, "$name.wav", "audio/x-wav") { WavWriter.write(it, samples, rate) }
                else null
                saveProgress = 1f
                SavedTake(name, if (vt != null) primary else null, primary, wav)
            }
            lastSaved = saved

            // Keep the take loaded so it can be opened in the editor.
            sampleRate = rate
            video = vt
            edl = whole
            undo.clear(); canUndo = false
            syncMs = 0
            title = name
            previewFrame = null
            setSamples(samples, resetSelection = true)
            requestFrame(0)
        } catch (e: Exception) {
            Log.e("TakeOne", "save failed", e)
            message = "Couldn't save the take: ${e.message}"
        } finally {
            saving = null
        }
    }

    // ---- Mac (CamLink) ---------------------------------------------------------------------

    /** Everything the Mac shows: camera abilities + recording state. Called from the WebView thread. */
    private fun capsJson(): String {
        val rec = Recorder.state.value
        return camera.capsJson().apply {
            put("app", true)
            put("recording", rec.status != RecStatus.IDLE)
            put("paused", rec.status == RecStatus.PAUSED)
            put("elapsedMs", if (rec.sampleRate > 0) rec.samples.toLong() * 1000L / rec.sampleRate else 0L)
            put("withVideo", videoEnabled && camera.bound)
            put("mic", selectedInput?.let { if (it.detail.isNotBlank()) "${it.label} (${it.detail.substringBefore(" ·")})" else it.label } ?: "Default mic")
            val reason = if (rec.status == RecStatus.IDLE) cannotRecordReason() else null
            put("canRecord", reason == null)
            if (reason != null) put("reason", reason)
            saving?.let { put("saving", "$it ${(saveProgress * 100).toInt()}%") }
            lastSaved?.let { put("lastSaved", it.name) }
        }.toString()
    }

    private fun handleMacCommand(m: JSONObject) {
        when (m.optString("type")) {
            "record" -> {
                when (m.optString("action")) {
                    "start" -> startRecording()
                    "stop" -> stopRecording()
                    "pause" -> pause()
                    "resume" -> resume()
                }
            }
            "flip" -> { if (!camera.flip()) message = "Can't switch cameras while recording." }
            "torch" -> { camera.setTorch(m.optBoolean("on")) }
            "zoom" -> { camera.setZoom(m.optDouble("value", 1.0).toFloat()) }
            "quality" -> { camera.streamMaxEdge = if (m.optInt("value") >= 1080) 1280 else 960 }
            else -> {}
        }
    }

    // ---- Editor ---------------------------------------------------------------------------

    fun openEditor() { if (take != null) { editorOpen = true; requestFrame(selStart) } }
    fun closeEditor() { player.stop(); playhead = null; editorOpen = false }

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

    /** Frees the loaded take (its saved files stay on the phone). */
    private fun releaseTake() {
        player.stop(); playhead = null
        editorOpen = false
        take = null
        envelope = FloatArray(0)
        undo.clear(); canUndo = false
        previewFrame = null
        edl = null
        val vt = video
        video = null
        if (vt != null) frameExecutor.execute { vt.release() } // after any frame grab in progress
    }

    // ---- Saving the edited version & sharing ----------------------------------------------

    /** Saves the edited take as a new file on the phone, then opens the share sheet. */
    fun saveEdited(format: ExportFormat, onReady: (Intent) -> Unit) {
        val s = take ?: return
        if (busy != null) return
        player.stop(); playhead = null
        busy = "Saving ${format.label}"
        progress = 0f
        val base = sanitize(title).ifBlank { "Take" } + " (edited)"
        val vt = video
        val e = edl
        val sync = syncMs
        val rate = sampleRate
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    when (format) {
                        ExportFormat.AAC -> {
                            val bytes = AacEncoder.encode(s, rate) { p -> progress = p }
                            MediaSaver.saveAudio(ctx, "$base.aac", "audio/aac") { it.write(bytes) }
                        }
                        ExportFormat.MP4 -> {
                            val audio = if (vt != null && e != null) alignAudio(s, rate, sync * 1_000_000L, e.durationUs) else s
                            val track = AacEncoder.encodeTrack(audio, rate) { p -> progress = p * 0.4f }
                            val tmp = File(takesDir, "edited.mp4")
                            Mp4Exporter.export(vt, e, track, tmp) { p -> progress = 0.4f + p * 0.5f }
                            val out = if (vt != null) MediaSaver.saveVideo(ctx, "$base.mp4", MediaSaver.copyFrom(tmp))
                            else MediaSaver.saveAudio(ctx, "$base.mp4", "audio/mp4", MediaSaver.copyFrom(tmp))
                            tmp.delete()
                            out
                        }
                        ExportFormat.WAV -> MediaSaver.saveAudio(ctx, "$base.wav", "audio/x-wav") { WavWriter.write(it, s, rate) }
                    }
                }
                message = "Saved to ${saved.folder}"
                onReady(shareIntent(saved.uri, saved.displayName))
            } catch (ex: Exception) {
                Log.e("TakeOne", "export failed", ex)
                message = "Couldn't save: ${ex.message}"
            } finally {
                busy = null
            }
        }
    }

    fun shareLast(onReady: (Intent) -> Unit) {
        val t = lastSaved ?: return
        onReady(shareIntent(t.mainFile.uri, t.mainFile.displayName))
    }

    private fun shareIntent(uri: Uri, name: String): Intent {
        val mime = ctx.contentResolver.getType(uri) ?: "*/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Post \"$name\"").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun sanitize(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60)

    override fun onCleared() {
        am.unregisterAudioDeviceCallback(deviceCallback)
        player.stop()
        camera.release()
        val vt = video
        if (vt != null) frameExecutor.execute { vt.release() }
        frameExecutor.shutdown()
        super.onCleared()
    }
}

enum class ExportFormat(val label: String, val hint: String) {
    MP4("MP4", "Video + sound (or sound only) — plays and posts almost anywhere"),
    WAV("WAV", "Sound only, lossless — best for editing elsewhere"),
    AAC("AAC", "Sound only, small file — for voice notes and audio apps"),
}
