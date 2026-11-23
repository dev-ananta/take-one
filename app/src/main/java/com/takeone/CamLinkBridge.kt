package com.takeone

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.takeone.video.CameraController
import org.json.JSONObject

/** Where the CamLink viewer lives (the page you open on the Mac). */
const val CAMLINK_URL = "https://camlink-xi.vercel.app"

/**
 * Connects TakeOne's camera to the CamLink page running in a small WebView.
 * The page pulls JPEG frames from here and streams them to the Mac over WebRTC,
 * and forwards the Mac's buttons (flip, light, zoom, quality) back to the camera.
 * Exposed to JavaScript as `window.TakeOne`.
 */
class CamLinkBridge(
    private val camera: CameraController,
    private val onStatus: (text: String, live: Boolean) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun frameId(): Int = camera.frameId()

    @JavascriptInterface fun frameData(): String = camera.frameData()

    @JavascriptInterface fun caps(): String = camera.capsJson()

    @JavascriptInterface
    fun status(text: String, live: Boolean) {
        main.post { onStatus(text, live) }
    }

    @JavascriptInterface
    fun command(json: String) {
        main.post {
            try {
                val m = JSONObject(json)
                when (m.optString("type")) {
                    "flip" -> { if (!camera.flip()) onMessage("Can't switch cameras while recording.") }
                    "torch" -> { camera.setTorch(m.optBoolean("on")) }
                    "zoom" -> { camera.setZoom(m.optDouble("value", 1.0).toFloat()) }
                    "quality" -> { camera.streamMaxEdge = if (m.optInt("value") >= 1080) 1280 else 960 }
                    else -> {}
                }
            } catch (_: Exception) {
            }
        }
    }
}
