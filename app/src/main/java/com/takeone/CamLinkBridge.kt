package com.takeone

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Where the CamLink viewer lives (the page you open on the Mac). */
const val CAMLINK_URL = "https://camlink-xi.vercel.app"

/**
 * Connects TakeOne to the CamLink page running in a small WebView (exposed to JavaScript as
 * `window.TakeOne`). The page pulls JPEG frames from here and streams them to the Mac, reports its
 * connection status, and forwards the Mac's buttons (Record/Stop, flip, light, zoom, quality).
 */
class CamLinkBridge(
    private val capsProvider: () -> String,
    private val onCommand: (JSONObject) -> Unit,
    private val onStatus: (text: String, live: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** Set by the camera so frames can be served. */
    var frameSource: com.takeone.video.CameraController? = null

    @JavascriptInterface fun frameId(): Int = frameSource?.frameId() ?: 0

    @JavascriptInterface fun frameData(): String = frameSource?.frameData() ?: ""

    @JavascriptInterface fun caps(): String = try { capsProvider() } catch (e: Exception) { "{\"type\":\"caps\"}" }

    @JavascriptInterface
    fun status(text: String, live: Boolean) {
        main.post { onStatus(text, live) }
    }

    @JavascriptInterface
    fun command(json: String) {
        main.post {
            try { onCommand(JSONObject(json)) } catch (_: Exception) {}
        }
    }
}
