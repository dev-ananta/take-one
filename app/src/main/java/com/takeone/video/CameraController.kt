package com.takeone.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the camera: on-screen preview, video recording into a RAM-only file, and a stream of
 * small JPEG frames for the live view on the Mac (CamLink). Sound is NOT recorded here — the
 * microphone is handled by [com.takeone.audio.Recorder] so the user's chosen mic is used.
 */
class CameraController(private val context: Context) {

    companion object {
        const val VIDEO_BITRATE = 6_000_000            // ~45 MB per minute in memory
        const val MAX_VIDEO_MINUTES = 10
        private const val TAG = "TakeOneCamera"
    }

    /** Result of a finished video recording. */
    class Finished(val file: MemFd, val startedAtBootNs: Long, val error: String?)

    var facingBack by mutableStateOf(true); private set
    var bound by mutableStateOf(false); private set
    var liveViewSupported by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var torchOn by mutableStateOf(false); private set

    private val main = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val preview = Preview.Builder().build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
        .setTargetVideoEncodingBitRate(VIDEO_BITRATE)
        .build()
    private val videoCapture = VideoCapture.withOutput(recorder)
    private val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

    // ---- Live-view frames for CamLink ------------------------------------------------------
    @Volatile var streaming = false
    @Volatile var streamMaxEdge = 960       // "1080p" in CamLink = 1280, "720p" = 960
    private val latestFrame = AtomicReference("")
    private val frameId = AtomicInteger(0)
    private var lastFrameAt = 0L

    fun frameId(): Int = frameId.get()
    fun frameData(): String = latestFrame.get()

    init {
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (streaming && now - lastFrameAt >= 55) { // ~18 fps is plenty for framing
                    lastFrameAt = now
                    val src = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    val scale = streamMaxEdge.toFloat() / maxOf(src.width, src.height)
                    val m = Matrix().apply {
                        if (scale < 1f) postScale(scale, scale)
                        if (rot != 0) postRotate(rot.toFloat())
                        // No mirroring here: the CamLink viewer mirrors the selfie camera itself.
                    }
                    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    val bytes = ByteArrayOutputStream(64 * 1024)
                    out.compress(Bitmap.CompressFormat.JPEG, 68, bytes)
                    if (out !== src) out.recycle()
                    src.recycle()
                    latestFrame.set(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    frameId.incrementAndGet()
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame", e)
            } finally {
                image.close()
            }
        }
    }

    // ---- Binding ---------------------------------------------------------------------------

    fun bind(lifecycleOwner: LifecycleOwner, previewSurface: Preview.SurfaceProvider) {
        owner = lifecycleOwner
        surfaceProvider = previewSurface
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                rebind()
            } catch (e: Exception) {
                error = "Camera unavailable: ${e.message}"
            }
        }, main)
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        bound = false
        torchOn = false
    }

    private fun rebind() {
        val p = provider ?: return
        val o = owner ?: return
        preview.setSurfaceProvider(surfaceProvider)
        val selector = if (facingBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        p.unbindAll()
        camera = try {
            liveViewSupported = true
            p.bindToLifecycle(o, selector, preview, videoCapture, analysis)
        } catch (e: Exception) {
            // Some phones can't run three camera streams at once; drop the Mac live view first.
            Log.w(TAG, "3-stream bind failed, retrying without live view", e)
            liveViewSupported = false
            try { p.bindToLifecycle(o, selector, preview, videoCapture) } catch (e2: Exception) {
                error = "Couldn't start the camera: ${e2.message}"; null
            }
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

    /** Capabilities in the JSON shape the CamLink viewer expects. */
    fun capsJson(): String {
        val c = camera
        val zoom = c?.cameraInfo?.zoomState?.value
        return JSONObject().apply {
            put("type", "caps")
            put("facing", if (facingBack) "environment" else "user")
            put("torch", c?.cameraInfo?.hasFlashUnit() == true)
            if (zoom != null && zoom.maxZoomRatio > zoom.minZoomRatio) {
                put("zoom", JSONObject().apply {
                    put("min", zoom.minZoomRatio.toDouble()); put("max", zoom.maxZoomRatio.toDouble())
                    put("step", 0.1); put("value", zoom.zoomRatio.toDouble())
                })
            }
            put("quality", if (streamMaxEdge >= 1280) 1080 else 720)
            put("app", true)
        }.toString()
    }

    // ---- Recording -------------------------------------------------------------------------

    private var recording: Recording? = null
    private var currentFile: MemFd? = null
    @Volatile private var startedAtBootNs = 0L
    val isRecording: Boolean get() = recording != null

    /** Starts recording video (no sound) into memory. [onFinished] is called on the main thread. */
    @SuppressLint("MissingPermission")
    @RequiresApi(30)
    fun startRecording(onFinished: (Finished) -> Unit): Boolean {
        if (!bound || recording != null) return false
        val file = MemFd.create("takeone-video")
        val options = FileDescriptorOutputOptions.Builder(file.pfd)
            .setFileSizeLimit(VIDEO_BITRATE / 8L * 60L * MAX_VIDEO_MINUTES)
            .build()
        currentFile = file
        startedAtBootNs = 0L
        recording = recorder.prepareRecording(context, options).start(main) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { startedAtBootNs = SystemClock.elapsedRealtimeNanos() }
                is VideoRecordEvent.Finalize -> {
                    val err = when (event.error) {
                        VideoRecordEvent.Finalize.ERROR_NONE,
                        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> null
                        else -> "Video recording failed (code ${event.error})"
                    }
                    recording = null
                    currentFile = null
                    onFinished(Finished(file, startedAtBootNs, err))
                }
                else -> {}
            }
        }
        return true
    }

    fun pause() { recording?.pause() }
    fun resume() { recording?.resume() }
    fun stopRecording() { recording?.stop() }

    fun release() {
        recording?.stop()
        unbind()
        analysisExecutor.shutdown()
    }
}
