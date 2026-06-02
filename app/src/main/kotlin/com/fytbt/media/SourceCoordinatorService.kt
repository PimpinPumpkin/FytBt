package com.fytbt.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the **MCU-source ↔ media coordination** alive independently of the
 * UI. This is the reliability backbone of the auto-pause feature: it must keep running while the
 * user is on the radio / a local player (i.e. while our Activity is backgrounded or even destroyed),
 * which a plain Activity-owned monitor can't guarantee — Android may tear a stopped Activity down
 * under memory pressure. A foreground service is the only no-root way to stay resident.
 *
 * How it works (all reverse-engineered on-device — see FINDINGS.md):
 *   - [SyuLink] reads the MCU's active audio source (its `APP_ID`) live over the SYU vendor IPC.
 *     This is the ONE signal that reflects EVERY source, because the FM radio and the unit's own
 *     players bypass Android audio focus entirely but still set the MCU APP_ID.
 *   - On every APP_ID change we reconcile playback so that *only the active source plays*:
 *       • APP_ID 3 (BTAV)  → the phone (Bluetooth) is the source. Resume it iff WE paused it.
 *       • APP_ID 8/9/10    → an on-unit player (Spotify-on-unit, local music) is the source.
 *       • anything else    → radio / AUX / phone-call / nothing: no media session is the source.
 *     Every *other* media session that is still PLAYING (muted by the MCU but advancing) gets
 *     paused, and remembered, so it can be resumed when its source comes back.
 *
 * Golden rule (learned through painful regressions): we ONLY ever resume a source we ourselves
 * auto-paused. A deliberate manual pause is never overridden — if it wasn't playing when the source
 * switched away, it isn't in our paused-set, so it's never auto-resumed.
 *
 * Control + state both go through [MediaSessionManager.getActiveSessions] (requires the
 * notification-listener to be enabled, which the app already needs for Now Playing). The
 * `com.android.bluetooth` AVRCP-controller session is the phone; everything else non-self is a
 * local player.
 */
class SourceCoordinatorService : Service() {

    private val syu by lazy { SyuLink(applicationContext) }
    private val msm by lazy {
        applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }
    private val listenerComponent by lazy {
        ComponentName(applicationContext, FytNotificationListener::class.java)
    }
    private val audioManager by lazy {
        applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    // We NEVER react to focus changes (see FINDINGS.md §6 — the A2DP sink grabs/releases focus as
    // normal playback, so reacting to a loss would kill the very audio we want). We request focus
    // only to *evict* a local app that's still holding it; the listener is a deliberate no-op.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* no-op by design */ }
    private var scope: CoroutineScope? = null

    // True while the phone (Bluetooth) is paused BECAUSE the MCU left Bluetooth.
    private var btPausedByMcu = false
    // On-unit players we auto-paused, keyed by session token, so we only resume what we paused.
    private val pausedLocalByMcu = HashSet<MediaSession.Token>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        syu.bind()
        scope?.launch {
            syu.appId.collect { id -> if (id != null) coordinate(id) }
        }
        Log.i(TAG, "SourceCoordinatorService created")
    }

    // Restart if the system kills us; we want to stay resident to keep coordinating sources.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { syu.unbind() }
        releaseFocus()
        scope?.cancel()
        scope = null
        Log.i(TAG, "SourceCoordinatorService destroyed")
        super.onDestroy()
    }

    /** Hold AUDIOFOCUS_GAIN to evict any local app still holding focus when Bluetooth resumes. */
    private fun acquireFocus() {
        val am = audioManager ?: return
        if (focusRequest != null) return // already held
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
        runCatching { am.requestAudioFocus(req) }
        focusRequest = req
    }

    private fun releaseFocus() {
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    /**
     * Reconcile playback to the new MCU source. See the class doc for the model. Mostly plain
     * [android.media.session.MediaController] transport calls — the MCU source selection (APP_ID),
     * not Android audio focus, governs *routing*. The ONE place focus matters: a local player we
     * paused (e.g. Symphony) can keep HOLDING Android audio focus, which suppresses the Bluetooth
     * sink output even though APP_ID is now 3 — so on a BT resume we request GAIN to evict it (see
     * [acquireFocus]). We never *react* to focus changes (the sink toggles focus during normal
     * playback; reacting would kill our own audio — FINDINGS.md §6).
     */
    private fun coordinate(appId: Int) {
        val sessions = runCatching { msm?.getActiveSessions(listenerComponent) }.getOrNull() ?: return
        val self = applicationContext.packageName
        val btIsSource = appId == SyuLink.APP_ID_BTAV
        val bt = sessions.firstOrNull { it.packageName == BT_PACKAGE }
        // If a local player is the source, that session should KEEP playing; everything else pauses.
        val localSourceToken = if (appId in LOCAL_PLAYER_APP_IDS)
            sessions.firstOrNull { it.packageName != BT_PACKAGE && it.packageName != self }?.sessionToken
        else null

        // --- The phone over Bluetooth -------------------------------------------------
        if (btIsSource) {
            if (bt != null && btPausedByMcu) {
                btPausedByMcu = false
                // CRITICAL: grab audio focus BEFORE resuming. A local player we paused (e.g.
                // Symphony) can keep HOLDING Android audio focus, which suppresses the Bluetooth
                // sink output even though the MCU source is now Bluetooth — the phone "plays" but
                // is silent until something evicts that stale focus holder. Requesting GAIN here
                // evicts it immediately (verified: Symphony abandons focus the instant we request),
                // so audio comes out at once instead of after a ~10s limbo. See FINDINGS.md §3/§5.
                acquireFocus()
                runCatching { bt.transportControls.play() }
                Log.i(TAG, "source -> Bluetooth: resume phone")
            }
        } else {
            if (bt != null && bt.playbackState?.state == PlaybackState.STATE_PLAYING) {
                runCatching { bt.transportControls.pause() }
                btPausedByMcu = true
                Log.i(TAG, "source -> appId=$appId: pause phone (Bluetooth)")
            }
            // We're no longer the Bluetooth source — don't keep holding focus.
            releaseFocus()
        }

        // --- On-unit players (Spotify-on-unit, local music) ---------------------------
        sessions.forEach { c ->
            if (c.packageName == self || c.packageName == BT_PACKAGE) return@forEach
            if (c.sessionToken == localSourceToken) {
                if (pausedLocalByMcu.remove(c.sessionToken)) {
                    runCatching { c.transportControls.play() }
                    Log.i(TAG, "source -> appId=$appId: resume ${c.packageName}")
                }
            } else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                runCatching { c.transportControls.pause() }
                pausedLocalByMcu.add(c.sessionToken)
                Log.i(TAG, "source -> appId=$appId: pause ${c.packageName}")
            }
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Audio source coordination", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps Bluetooth/radio/local audio from playing over each other."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FytBt")
            .setContentText("Coordinating audio sources")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "FytBt"
        private const val CHANNEL_ID = "fytbt_source_coord"
        private const val NOTIF_ID = 7001
        private const val BT_PACKAGE = "com.android.bluetooth"
        // MCU APP_IDs that map to an on-unit media player (FinalMain): AUDIO_PLAYER=8, VIDEO=9,
        // THIRD_PLAYER=10 (e.g. Spotify-on-unit).
        private val LOCAL_PLAYER_APP_IDS = setOf(8, 9, 10)

        /** Start (or no-op if already running) the coordinator. Safe to call from the Activity. */
        fun start(context: Context) {
            val intent = Intent(context, SourceCoordinatorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start coordinator: ${it.message}") }
        }
    }
}
