# TakeOne — video + external-mic recorder with Mac remote (Android)

Record **video from the phone camera + sound from a connected microphone** (USB, wired, line-in or
Bluetooth) while your phone keeps **playing audio to your headphones**. Watch the camera live on
your Mac with **CamLink** and press **Record / Stop right from the Mac**. Every take is saved to the
phone automatically.

## What's new in v3
- **Record from the Mac.** CamLink (camlink-xi.vercel.app) now has a **● Record / ■ Stop** button
  (or press **R**). The Mac shows a REC timer, which mic is being used, and saving progress, then
  "Saved on phone". TakeOne stays on the camera screen, so you can go take after take from the Mac.
- **Takes save automatically:**
  - `Movies/TakeOne/TakeOne <date time>.mp4` — video + your mic's sound, lined up.
  - `Music/TakeOne/TakeOne <date time>.wav` — the mic on its own, lossless, for editing
    (switch: *Also save the mic as WAV*).
- **More reliable video:** the camera records to a normal file using only the two camera streams
  every phone supports (preview + recording). The Mac live view is copied from the on-screen
  preview, so it can't interfere with recording. Works on Android 8+.
- The on-screen preview has the same shape as the video, so the framing on the phone and on the
  Mac is exactly what gets recorded.
- Rotating the phone no longer restarts the camera.

## Using it
1. Open TakeOne. Allow microphone and camera (asked once at launch).
2. Plug in your mic — it's picked automatically (check the **Microphone** card).
3. On the Mac open **camlink-xi.vercel.app** → *I'm on the Mac*. In TakeOne, type the code under
   **Live view on Mac** → **Connect**.
4. Press **● Record** on the Mac (or the big button on the phone). Press **■ Stop** when done.
5. The take saves to the phone (a few seconds per minute of video). The **Last take** card has
   **Edit** and **Share…**.

Keep TakeOne open on the phone's screen while recording video (it keeps the screen awake) —
Android stops the camera if the phone locks.

## Editing (optional)
**Edit** opens the last take: trim, cut, fade, normalize, ±3 dB, undo, lip-sync nudge.
Video cuts snap to keyframes (about once a second) so the video isn't re-encoded.
**Save & post…** saves the edited version as a new file (`… (edited)`) — MP4, WAV or AAC — and
opens the share sheet. The original take is never changed. While the editor is open the Mac live
view pauses; close the editor to resume.

## Notes
- The camera records no sound of its own; the sound always comes from the mic you selected.
- Takes can be up to 20 minutes (sound is lined up and edited in memory).
- The camera can't flip while recording (Android would end the recording).
- Bluetooth mics use the phone's call-quality link; USB or wired mics sound much better.
- Live view on Mac needs internet on both devices (video goes phone → Mac directly, encrypted).

## If video still doesn't record
Plug the phone into Android Studio and open **Logcat**, filter by `TakeOneCamera`. The exact reason
is logged there, and also shown in the app's message bar after a take (e.g. "CameraX error …").
Send me that line and I can fix it precisely.

## Build & install
1. Install **Android Studio**. *File → Open* → this `TakeOne` folder; let Gradle sync.
2. Run ▶ on your phone, or *Build → Build APK(s)* / `./gradlew assembleDebug`.

Requires Android 8.0+. Kotlin + Jetpack Compose + CameraX.

## Project layout
```
app/src/main/java/com/takeone/
  MainActivity.kt          permissions, recorder ↔ editor
  RecorderViewModel.kt     devices, record → line up → save pipeline, Mac commands, edits, export
  MediaSaver.kt            saves to Movies/TakeOne and Music/TakeOne
  CamLinkBridge.kt         talks to the CamLink page (frames out, Mac buttons in)
  RecordingService.kt      foreground service for the mic
  audio/                   mic capture & routing, PCM edits, WAV/AAC, playback
  video/CameraController.kt  CameraX preview + recording, live-view frames from the preview
  video/VideoTake.kt       reads keyframes/size/rotation, grabs editor frames
  video/Edl.kt             keyframe-safe trim/cut + audio alignment (unit-tested)
  video/Mp4Exporter.kt     muxes video + AAC into .mp4
  ui/                      screens
```
