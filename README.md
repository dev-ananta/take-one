# TakeOne — memory-only video & audio recorder (Android)

Record **video from the phone camera + sound from a connected microphone** (USB, wired, line-in or
Bluetooth) while your phone keeps **playing audio to your headphones**. Watch the camera **live on
your Mac** while you record, edit the take, then post it as an **.mp4** — without the recording
ever being saved to the phone.

## What's new in v2
- **Video recording.** Camera video and your chosen mic are recorded together. Turn the Camera
  switch off for sound-only takes.
- **Live view on Mac, built in.** No browser needed on the phone. On the Mac open
  **camlink-xi.vercel.app**, choose *I'm on the Mac*, then in TakeOne type the code into
  **Live view on Mac** and tap **Connect**. The Mac's Flip / Light / Zoom / quality buttons control
  TakeOne's camera.
- **MP4 instead of WAV.** Posts as `.mp4` (video + sound, or sound only). AAC is still offered for
  small voice-note style files.
- **Lip sync** control in the editor, in case sound and picture need a nudge.

## How "never saved to the device" works
- Sound is captured as raw PCM straight into RAM.
- Video is written by CameraX into a **memfd** — a RAM-only file the kernel keeps in memory. It is
  never on storage and vanishes when the app closes.
- The final MP4 is built in another memfd and handed to the share sheet through a virtual,
  seekable file descriptor (`StorageManager.openProxyFileDescriptor`). Nothing is written to disk.
- `allowBackup` is off. Discard, a new take, or closing the app wipes everything.

## Mic & headphones
- The mic picker pins your choice with `AudioRecord.setPreferredDevice`, so Android doesn't switch
  to the headset or built-in mic. External mics are auto-selected when plugged in.
- TakeOne never takes audio focus, so music/video from other apps keeps playing.
- The camera records **no sound of its own** — the sound track always comes from the mic you chose.

## Editing
Drag the waveform handles to select a section, then Trim, Cut, Fade in/out, Normalize, ±3 dB, Undo.
For video takes, the picture above the waveform follows the playhead and handles.
**Trims and cuts snap to the video's keyframes (about one per second)** — that's what lets the video
be cut without re-encoding, so there's zero quality loss. The app tells you when it moved an edge.

## Limits / notes
- **Video needs Android 11+** (RAM-only files). On older phones TakeOne records sound only.
- **Keep TakeOne on screen while recording video** (it keeps the screen awake). Android stops the
  camera if the phone locks. Sound-only takes keep recording in the background.
- Video takes: up to **10 minutes** (1080p at 6 Mbps ≈ 45 MB/min of RAM; exporting briefly needs
  about twice that). Sound-only takes: up to 20 minutes.
- Lip sync is lined up automatically from capture timestamps; if a phone is off, use the slider.
- The camera can't flip while recording (Android would end the recording).
- A few phones can't run the preview, recording and Mac live view at once; TakeOne then keeps
  recording and pauses the live view.
- Bluetooth mics use the phone's call-quality link; USB or wired mics sound much better.
- Live view on Mac needs internet on both devices (the video goes phone → Mac directly, encrypted).

## Build & install
1. Install **Android Studio**. *File → Open* → this `TakeOne` folder; let Gradle sync.
2. Run ▶ on your phone, or *Build → Build APK(s)* / `./gradlew assembleDebug`.
3. Allow microphone and camera when asked.

Requires Android 8.0+ (video: Android 11+). Kotlin + Jetpack Compose + CameraX.

## Project layout
```
app/src/main/java/com/takeone/
  MainActivity.kt          permissions, recorder ↔ editor
  RecorderViewModel.kt     devices, recording (mic + camera), edits, undo, export
  RecordingService.kt      foreground service for the mic
  MemoryAudioProvider.kt   shares bytes / RAM-only files without writing to storage
  CamLinkBridge.kt         feeds camera frames to the CamLink page, takes the Mac's commands
  audio/                   mic capture, device routing, PCM edits, AAC encoding, playback
  video/CameraController.kt  CameraX preview + recording into memory + live-view frames
  video/MemFd.kt           RAM-only files
  video/VideoTake.kt       reads keyframes/size/rotation, grabs preview frames
  video/Edl.kt             keyframe-safe trim/cut list + audio alignment (unit-tested)
  video/Mp4Exporter.kt     copies kept video pieces + muxes AAC into the final .mp4
  ui/Screens.kt, ui/CameraUi.kt, ui/Waveform.kt, ui/Theme.kt
```
