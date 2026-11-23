# TakeOne — memory-only recorder & editor (Android)

Record from a **connected microphone** (USB, wired, line-in or Bluetooth) while your phone keeps
**playing audio to your headphones**, edit the take, then post it — **without the recording ever
being saved to the device**.

## How it meets the brief
**Never saved to the device**
- Audio is captured as raw PCM straight into RAM (chunked buffers), edited in RAM, and encoded in RAM.
- Posting uses Android's share sheet. Instead of writing a temp file, a small content provider
  serves the bytes through `StorageManager.openProxyFileDescriptor` — a virtual, seekable file
  whose data comes from memory. The receiving app (WhatsApp, Telegram, Drive, Gmail, SoundCloud…)
  reads it directly.
- `allowBackup` is off. Discarding the take, starting a new one, or closing the app wipes it.
  (What the app you post *to* does with the file is up to that app.)

**Connected mic while audio plays to headphones**
- The mic picker lists every input and pins your choice with `AudioRecord.setPreferredDevice`,
  so Android doesn't silently switch to the headset or built-in mic. External mics are auto-selected
  when plugged in.
- TakeOne never takes audio focus, so music/video from other apps keeps playing to your headphones
  while you record. A foreground service keeps recording when you switch apps or lock the screen.
- Only the selected mic is recorded — not what's playing in your headphones.

## Editing
Drag the two handles on the waveform to select a section, then:
- **Trim to selection** / **Cut selection**
- **Fade in** / **Fade out** (1.5 s at the start/end of the selection)
- **Normalize** (peak to −0.3 dBFS), **−3 dB / +3 dB**
- **Undo** (up to 5 steps; 1 step for takes longer than 4 min, to save memory)
- **Play** previews the selection through your current output.

## Posting
**Post…** → choose **AAC** (small, ~1.2 MB/min) or **WAV** (lossless, ~5.5 MB/min) → pick the app.

## Limits / notes
- Max take length: 20 minutes (memory). Mono, 48 kHz (falls back to 44.1 kHz).
- **Bluetooth mics** only work through the phone's call-audio link (SCO), which is low-fi and can
  put Bluetooth headphones into call mode. For good quality use a **USB or wired mic**. The ideal
  setup is a USB mic + Bluetooth or wired headphones.
- "Raw" processing appears only on phones that support unprocessed capture.
- Apps that only accept video (e.g. Instagram/TikTok posts) won't show up in the share sheet for audio.

## Build & install
1. Install **Android Studio**. *File → Open* → this `TakeOne` folder; let Gradle sync.
2. Run ▶ on your phone, or *Build → Build APK(s)* / `./gradlew assembleDebug`.
3. Grant microphone (and notification) permission on first record.

Requires Android 8.0+ (API 26). Kotlin + Jetpack Compose, no third-party libraries.

## Project layout
```
app/src/main/java/com/takeone/
  MainActivity.kt          permissions, switches between recorder and editor
  RecorderViewModel.kt     device list, recording, edits, undo, export
  RecordingService.kt      foreground service (keeps the mic alive in background)
  MemoryAudioProvider.kt   shares bytes from RAM via a virtual file descriptor
  audio/Recorder.kt        AudioRecord capture into memory, preferred-device routing
  audio/Devices.kt         input/output discovery and labels
  audio/AudioEdits.kt      trim/cut/fade/normalize/gain, WAV + ADTS writers
  audio/AacEncoder.kt      MediaCodec AAC encoding in memory
  audio/Player.kt          AudioTrack preview playback
  ui/Screens.kt, ui/Waveform.kt, ui/Theme.kt
```
