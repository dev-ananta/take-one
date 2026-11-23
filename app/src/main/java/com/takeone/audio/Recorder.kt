package com.takeone.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.takeone.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * A finished recording, held only in RAM.
 * [startedAtBootNs] is when sample 0 was captured (SystemClock.elapsedRealtimeNanos clock), used to
 * line the audio up with video. 0 if unknown.
 */
class Take(val samples: ShortArray, val sampleRate: Int, val startedAtBootNs: Long = 0L)

enum class RecStatus { IDLE, RECORDING, PAUSED }

data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val samples: Int = 0,
    val sampleRate: Int = 48000,
    val level: Float = 0f,
    val history: List<Float> = emptyList(),
    val deviceLabel: String = "",
    val error: String? = null,
    val hitLimit: Boolean = false,
)

/**
 * Captures PCM from a chosen input device straight into memory.
 *
 * - The selected mic is pinned with [AudioRecord.setPreferredDevice], so a USB / wired /
 *   Bluetooth mic keeps being used even while playback goes to headphones.
 * - It never requests audio focus, so music or video playing in other apps keeps playing.
 * - Nothing is written to storage. When recording stops, the take is exposed via [finished].
 */
object Recorder {
    const val MAX_MINUTES = 20
    private const val HISTORY = 160

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    private val _finished = MutableStateFlow<Take?>(null)
    val finished: StateFlow<Take?> = _finished.asStateFlow()

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    fun consumeFinished(): Take? = _finished.value.also { _finished.value = null }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before calling.
    fun start(context: Context, device: InputDevice?, mode: SourceMode): Boolean {
        if (running) return false
        val app = context.applicationContext
        val am = app.getSystemService(AudioManager::class.java)

        val useBluetooth = device?.isBluetooth == true
        if (useBluetooth) enableBluetoothMic(am, device!!.info)

        val source = when {
            useBluetooth -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            mode == SourceMode.RAW && !supportsRaw(am) -> MediaRecorder.AudioSource.MIC
            else -> mode.source
        }

        var record: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(48000, 44100)) {
            val min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(candidate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(min * 4, candidate)) // ~0.5 s of headroom
                    .build()
            } catch (e: Exception) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                record = r; rate = candidate; break
            }
            r?.release()
        }
        if (record == null) {
            if (useBluetooth) disableBluetoothMic(am)
            _state.value = RecState(error = "Couldn't open the microphone. Another app may be using it.")
            return false
        }
        if (device != null) record.setPreferredDevice(device.info)

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            if (useBluetooth) disableBluetoothMic(am)
            _state.value = RecState(error = "Couldn't start recording: ${e.message}")
            return false
        }

        running = true
        paused = false
        _finished.value = null
        val label = device?.label ?: "Default mic"
        _state.value = RecState(RecStatus.RECORDING, 0, rate, 0f, emptyList(), label)

        val rec: AudioRecord = record
        val sampleRate = rate
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val store = ChunkStore(sampleRate * 5)
            val buf = ShortArray(sampleRate / 50) // 20 ms reads
            val history = ArrayDeque<Float>(HISTORY)
            val maxSamples = sampleRate * 60 * MAX_MINUTES
            var lastEmit = 0L
            var error: String? = null
            var hitLimit = false
            var actualDevice = label
            var startBootNs = 0L
            var fallbackStart = 0L
            var tsTries = 0
            val ts = AudioTimestamp()

            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { error = "The microphone stopped responding (code $n)."; break }
                if (n == 0) continue
                // Work out when the very first sample was captured (for audio/video sync).
                if (startBootNs == 0L && tsTries < 50) {
                    tsTries++
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        startBootNs = ts.nanoTime - ts.framePosition * 1_000_000_000L / sampleRate
                    } else if (tsTries == 50) {
                        startBootNs = -1L
                    }
                }
                if (store.size == 0 && startBootNs <= 0L && fallbackStart == 0L) {
                    fallbackStart = SystemClock.elapsedRealtimeNanos() - n * 1_000_000_000L / sampleRate
                }
                var peak = 0
                for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
                val level = peak / 32768f
                if (!paused) {
                    store.append(buf, n)
                    if (history.size == HISTORY) history.removeFirst()
                    history.addLast(level)
                }
                if (store.size >= maxSamples) { hitLimit = true; break }

                val now = System.nanoTime()
                if (now - lastEmit > 40_000_000L) {
                    lastEmit = now
                    rec.routedDevice?.let { routed ->
                        if (device != null && routed.id != device.info.id) {
                            actualDevice = "${inputLabel(routed.type)} (requested ${device.label})"
                        }
                    }
                    _state.value = RecState(
                        status = if (paused) RecStatus.PAUSED else RecStatus.RECORDING,
                        samples = store.size, sampleRate = sampleRate, level = level,
                        history = history.toList(), deviceLabel = actualDevice,
                    )
                }
            }

            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            if (useBluetooth) disableBluetoothMic(am)
            running = false

            val samples = store.flatten()
            val start = if (startBootNs > 0L) startBootNs else fallbackStart
            // Always report the end of a take (even an empty one) so a video recording can be paired with it.
            _finished.value = Take(samples, sampleRate, start)
            _state.value = RecState(sampleRate = sampleRate, error = error, hitLimit = hitLimit)
            // Ask the foreground service to wind down (it stops itself once it has started).
            try {
                app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_FINISHED))
            } catch (_: Exception) {
                app.stopService(Intent(app, RecordingService::class.java))
            }
        }, "TakeOne-recorder").also { it.start() }
        return true
    }

    fun pause() { if (running) paused = true }
    fun resume() { if (running) paused = false }
    fun stop() { running = false }
    val isRunning: Boolean get() = running

    // ---- Bluetooth mics need the phone's "call audio" link (SCO) ---------------------------

    private fun enableBluetoothMic(am: AudioManager, input: AudioDeviceInfo) {
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val target = am.availableCommunicationDevices.firstOrNull {
                    it.type == input.type && it.address == input.address
                } ?: am.availableCommunicationDevices.firstOrNull { it.type == input.type }
                if (target != null) am.setCommunicationDevice(target)
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
            }
        } catch (_: Exception) {
        }
    }

    private fun disableBluetoothMic(am: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }
}
