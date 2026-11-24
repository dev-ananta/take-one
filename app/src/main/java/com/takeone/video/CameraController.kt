package com.takeone.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the camera: the on-screen preview and video recording to a file. Only the two streams every
 * Android phone supports are used (preview + video). The live view for the Mac is taken from the
 * on-screen preview, so it never competes with the recording.
 *
 * Sound is NOT recorded here — the microphone is handled by [com.takeone.audio.Recorder] so the
 * user's chosen mic is used.
 */
class CameraController(private val context: Context) {

    companion object {
        const val VIDEO_BITRATE = 8_000_000
        private const val TAG = "TakeOneCamera"
    }

    /** Result of a finished video recording. */
    class Finished(val file: File, val startedAtBootNs: Long, val error: String?)

    var facingBack by mutableStateOf(true); private set
    var bound by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var torchOn by mutableStateOf(false); private set

    private val main = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private val sixteenByNine = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build()
    private val preview = Preview.Builder().setResolutionSelector(sixteenByNine).build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
        .setAspectRatio(AspectRatio.RATIO_16_9)
        .setTargetVideoEncodingBitRate(VIDEO_BITRATE)
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)

    // ---- Live-view frames for CamLink (grabbed from the on-screen preview) -------------------
    @Volatile var streaming = false
        set(value) { field = value; if (value) scheduleGrab() }
    @Volatile var streamMaxEdge = 960       // "1080p" in CamLink = 1280, "720p" = 960
    private val latestFrame = AtomicReference("")
    private val frameId = AtomicInteger(0)
    @Volatile private var encoding = false
    private var grabScheduled = false

    fun frameId(): Int = frameId.get()
    fun frameData(): String = latestFrame.get()

    private fun scheduleGrab() {
        if (grabScheduled) return
        grabScheduled = true
        mainHandler.postDelayed(grabTask, 66) // ~15 fps
    }

    private val grabTask = object : Runnable {
        override fun run() {
            grabScheduled = false
            if (!streaming) return
            val view = previewView
            if (view != null && bound && !encoding) {
                val bmp = try { view.bitmap } catch (e: Exception) { null }
                if (bmp != null) {
                    encoding = true
                    val mirror = !facingBack
                    encodeExecutor.execute { encode(bmp, mirror) }
                }
            }
            scheduleGrab()
        }
    }

    private fun encode(src: Bitmap, unmirror: Boolean) {
        try {
            val scale = streamMaxEdge.toFloat() / maxOf(src.width, src.height)
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                // The on-screen selfie preview is mirrored; send the true image (the Mac mirrors it itself).
                if (unmirror) postScale(-1f, 1f)
            }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val bytes = ByteArrayOutputStream(64 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
            if (out !== src) out.recycle()
            src.recycle()
            latestFrame.set(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
            frameId.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "frame", e)
        } finally {
            encoding = false
        }
    }

    // ---- Binding ---------------------------------------------------------------------------

    fun bind(lifecycleOwner: LifecycleOwner, view: PreviewView) {
        owner = lifecycleOwner
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                rebind()
            } catch (e: Exception) {
                error = "Camera unavailable: ${e.message}"
            }
        }, main)
        if (streaming) scheduleGrab()
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        previewView = null
        bound = false
        torchOn = false
    }

    private fun rebind() {
        val p = provider ?: return
        val o = owner ?: return
        val view = previewView ?: return
        preview.setSurfaceProvider(view.surfaceProvider)
        val selector = if (facingBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        p.unbindAll()
        camera = try {
            p.bindToLifecycle(o, selector, preview, videoCapture)
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            error = "Couldn't start the camera: ${e.message}"
            null
        }
        bound = camera != null
        torchOn = false
        if (bound) error = null
    }

    /** Switches front/back. Not allowed while recording (it would end the recording). */
    fun flip(): Boolean {
        if (recording != null) return false
        facingBack = !facingBack
        rebind()
        return true
    }

    fun setTorch(on: Boolean) {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) return
        c.cameraControl.enableTorch(on)
        torchOn = on
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** Camera capabilities in the JSON shape the CamLink viewer expects. */
    fun capsJson(): JSONObject {
        val c = camera
        val zoom = c?.cameraInfo?.zoomState?.value
        return JSONObject().apply {
            put("type", "caps")
            put("facing", if (facingBack) "environment" else "user")
            put("torch", c?.cameraInfo?.hasFlashUnit() == true)
            put("torchOn", torchOn)
            if (zoom != null && zoom.maxZoomRatio > zoom.minZoomRatio) {
                put("zoom", JSONObject().apply {
                    put("min", zoom.minZoomRatio.toDouble()); put("max", zoom.maxZoomRatio.toDouble())
                    put("step", 0.1); put("value", zoom.zoomRatio.toDouble())
                })
            }
            put("quality", if (streamMaxEdge >= 1280) 1080 else 720)
        }
    }

    // ---- Recording -------------------------------------------------------------------------

    private var recording: Recording? = null
    @Volatile private var startedAtBootNs = 0L
    val isRecording: Boolean get() = recording != null

    /** Starts recording video (no sound) into [file]. [onFinished] is called on the main thread. */
    @SuppressLint("MissingPermission")
    fun startRecording(file: File, onFinished: (Finished) -> Unit): Boolean {
        if (!bound || recording != null) return false
        // Record in the orientation the phone is held right now.
        previewView?.display?.rotation?.let { videoCapture.targetRotation = it }
        startedAtBootNs = 0L
        return try {
            recording = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
                .start(main) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> { startedAtBootNs = SystemClock.elapsedRealtimeNanos() }
                        is VideoRecordEvent.Finalize -> {
                            val err = when (event.error) {
                                VideoRecordEvent.Finalize.ERROR_NONE,
                                VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                                VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> null
                                VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                                    if (file.length() > 0) null else "The camera stopped before any video was recorded"
                                VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Phone storage is full"
                                VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "No video was captured (take too short?)"
                                else -> "Video recording failed (CameraX error ${event.error}: ${event.cause?.message ?: "unknown"})"
                            }
                            if (err != null) Log.e(TAG, "finalize: $err", event.cause)
                            recording = null
                            onFinished(Finished(file, startedAtBootNs, err))
                        }
                        else -> {}
                    }
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            error = "Couldn't start video: ${e.message}"
            recording = null
            false
        }
    }

    fun pause() { recording?.pause() }
    fun resume() { recording?.resume() }
    fun stopRecording() { recording?.stop() }

    fun release() {
        streaming = false
        recording?.stop()
        unbind()
        encodeExecutor.shutdown()
    }
}
