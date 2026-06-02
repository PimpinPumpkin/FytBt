package com.fytbt.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Pokes the FYT/SYU stock vendor services to make the head unit's MCU select Bluetooth as the
 * active audio source (so phone audio actually comes out of the speakers, and radio/local
 * playback is muted).
 *
 * What actually works (determined empirically on-device by watching the `sound`/`Qin` MCU logs):
 *  - `com.syu.bt.byav.force` -> MyService: received, but did NOT switch the source on its own.
 *  - `com.syu.bt.byav.widgetPlayPause` -> BtavPlayPauseService: THIS is the real lever. It makes
 *    the stock BtMusic component request audio focus, which drives the MCU to switch appId to 3
 *    (Bluetooth) — `Qin: UI Change AppId to 3`, `volBt=36`, local player muted. Side effect: it's
 *    a play/PAUSE toggle, so callers must re-assert play afterward to guarantee playback.
 *
 * All wrapped in runCatching so a unit without the stock app degrades to a no-op.
 */
object SyuBridge {
    private const val TAG = "FytBt"
    private const val PKG = "com.syu.bt"

    private const val SVC_MAIN = "com.syu.broadcast.MyService"
    private const val ACTION_BYAV_FORCE = "com.syu.bt.byav.force"

    private const val SVC_PLAYPAUSE = "com.syu.broadcast.BtavPlayPauseService"
    private const val ACTION_WIDGET_PLAYPAUSE = "com.syu.bt.byav.widgetPlayPause"

    // widgetPlayPause is a play/PAUSE TOGGLE — firing it twice in quick succession cancels out. Two
    // independent callers can want to claim BT at almost the same moment (the UI on app-open AND the
    // coordinator service on the resulting APP_ID->3), so self-debounce here to guarantee a single
    // toggle per claim regardless of who calls.
    private const val DEBOUNCE_MS = 1500L
    @Volatile private var lastFireMs = 0L

    /**
     * Make Bluetooth the active MCU audio source (stops radio / local player). Fires the byav.force
     * hint first, then the widgetPlayPause lever that actually flips appId to BT and makes the stock
     * BtMusic component grab audio focus + route the sink. Because the lever toggles play/pause, the
     * caller should assert AVRCP play shortly after. Self-debounced (see [DEBOUNCE_MS]).
     */
    fun switchSourceToBluetooth(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFireMs < DEBOUNCE_MS) {
            Log.i(TAG, "switchSourceToBluetooth: debounced (${now - lastFireMs}ms since last)")
            return false
        }
        lastFireMs = now
        return runCatching {
            context.startService(Intent(ACTION_BYAV_FORCE).setComponent(ComponentName(PKG, SVC_MAIN)))
            context.startService(
                Intent(ACTION_WIDGET_PLAYPAUSE).setComponent(ComponentName(PKG, SVC_PLAYPAUSE))
            )
            Log.i(TAG, "SYU switchSourceToBluetooth dispatched (byav.force + widgetPlayPause)")
            true
        }.getOrElse {
            Log.w(TAG, "SYU switchSourceToBluetooth failed (stock app missing/renamed?): ${it.message}")
            false
        }
    }
}
