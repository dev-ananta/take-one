package com.takeone.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

/** A microphone the user can pick. */
data class InputDevice(
    val id: Int,
    val type: Int,
    val label: String,
    val detail: String,
    val info: AudioDeviceInfo,
) {
    val isBluetooth: Boolean
        get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == TYPE_BLE_HEADSET

    val isExternal: Boolean get() = type != AudioDeviceInfo.TYPE_BUILTIN_MIC
}

/** Recording "flavours" mapped to Android audio sources. */
enum class SourceMode(val label: String, val hint: String, val source: Int) {
    STANDARD("Standard", "Phone's normal mic processing", MediaRecorder.AudioSource.MIC),
    RAW("Raw", "No noise suppression or auto-gain — best for good mics", MediaRecorder.AudioSource.UNPROCESSED),
    VOICE("Voice", "Tuned for speech", MediaRecorder.AudioSource.VOICE_RECOGNITION),
}

// Constants added in newer API levels, referenced by value so the app still compiles cleanly.
const val TYPE_BLE_HEADSET = 26
private const val TYPE_ECHO_REFERENCE = 28

private val HIDDEN_INPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_TELEPHONY,
    AudioDeviceInfo.TYPE_FM_TUNER,
    AudioDeviceInfo.TYPE_TV_TUNER,
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    TYPE_ECHO_REFERENCE,
)

fun inputLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB mic"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset mic"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
    TYPE_BLE_HEADSET -> "Bluetooth LE mic"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line in"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line in"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI input"
    AudioDeviceInfo.TYPE_DOCK -> "Dock input"
    else -> "Audio input"
}

/** External mics first (USB, then wired, then Bluetooth), built-in last. */
private fun inputPriority(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_USB_DEVICE -> 0
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 1
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 2
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> 4
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> 9
    else -> 5
}

fun listInputs(am: AudioManager): List<InputDevice> =
    am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.isSource && it.type !in HIDDEN_INPUT_TYPES }
        .map { d ->
            val name = d.productName?.toString().orEmpty()
            val detail = buildList {
                if (name.isNotBlank() && name != Build.MODEL) add(name)
                if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && d.address.isNotBlank()) add(d.address)
                val rates = d.sampleRates
                if (rates.isNotEmpty()) add("up to ${rates.max() / 1000} kHz")
            }.joinToString(" · ")
            InputDevice(d.id, d.type, inputLabel(d.type), detail, d)
        }
        .sortedBy { inputPriority(it.type) }

/** Plain-language description of where sound is currently playing. */
fun describeOutputs(am: AudioManager): String {
    val names = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d ->
        val n = d.productName?.toString().orEmpty().takeIf { it.isNotBlank() && it != Build.MODEL }
        when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headphones" + (n?.let { " ($it)" } ?: "")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth" + (n?.let { " ($it)" } ?: "")
            27 /* TYPE_BLE_SPEAKER */, TYPE_BLE_HEADSET -> "Bluetooth LE" + (n?.let { " ($it)" } ?: "")
            else -> null
        }
    }.distinct()
    return if (names.isEmpty()) "Phone speaker" else names.joinToString(", ")
}

fun supportsRaw(am: AudioManager): Boolean =
    am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
