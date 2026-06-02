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
import kotlinx.coroutines.delay
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
     * Reconcile playback to the new MCU source. See the class doc for the model.
     *
     * The pause side is plain [android.media.session.MediaController] transport calls. The RESUME
     * side is the subtle one: just calling play() on the BT session leaves it **silent** — the phone
     * shows "playing" but no audio comes out for ~10 s. Routing on this unit requires the STOCK
     * BtMusic component to grab audio focus and re-assert the sink (exactly what a manual pause/play
     * toggle on the stereo does), which is the `widgetPlayPause` lever in [SyuBridge]. So on a BT
     * resume we fire that lever (+ grab focus first to evict any local app like Symphony still
     * holding it, which would otherwise suppress the sink) and assert play a beat later. We never
     * *react* to focus changes (the sink toggles focus during normal playback; reacting would kill
     * our own audio — FINDINGS.md §6).
     */
    private fun coordinate(appId: Int) {
        currentAppId = appId
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
            // Consume the suppress flag on any return to BT (set by an app-open take-over that
            // doesn't want auto-play). Consumed even when there's nothing to resume, so it never
            // goes stale and blocks a later legitimate auto-resume.
            val suppressed = suppressAutoResume
            suppressAutoResume = false
            if (bt != null && btPausedByMcu) {
                btPausedByMcu = false
                if (suppressed) {
                    // App-open take-over: the UI already switched the source and is asserting pause.
                    // Don't auto-play — opening the app must not start a phone you didn't start.
                    Log.i(TAG, "source -> Bluetooth: auto-resume suppressed (app-open take-over)")
                } else {
                    // Route audio the way the stock app does. Bare play() here is silent for ~10s; the
                    // stock widgetPlayPause lever (= what a manual toggle does) makes BtMusic grab focus
                    // and re-assert the sink. Grab focus first to evict a focus-holding local player
                    // (e.g. Symphony) that would otherwise keep the sink muted. See FINDINGS.md §3/§5.
                    acquireFocus()
                    SyuBridge.switchSourceToBluetooth(applicationContext)
                    scope?.launch {
                        delay(RESUME_PLAY_DELAY_MS)
                        runCatching { bt.transportControls.play() }
                    }
                    Log.i(TAG, "source -> Bluetooth: resume phone (claim + route)")
                }
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
        // The SYU source switch toggles play/pause, so we assert play a beat after firing it.
        private const val RESUME_PLAY_DELAY_MS = 700L
        // MCU APP_IDs that map to an on-unit media player (FinalMain): AUDIO_PLAYER=8, VIDEO=9,
        // THIRD_PLAYER=10 (e.g. Spotify-on-unit).
        private val LOCAL_PLAYER_APP_IDS = setOf(8, 9, 10)

        /**
         * Latest MCU active-source APP_ID seen by the coordinator (-1 until first read). Lets the UI
         * decide whether opening the app needs to claim Bluetooth: if BT isn't already the source,
         * claim it (kill the radio / pause local players); if it already is, leave playback alone so
         * a manual pause isn't overridden.
         */
        @Volatile
        var currentAppId: Int = -1
            private set

        /**
         * Set by the UI just before an app-open take-over (claim BT without playing). The coordinator
         * consumes it on the next return to BT and skips its auto-resume, so opening the app kills
         * other sources without force-starting the phone. One-shot.
         */
        @Volatile
        var suppressAutoResume: Boolean = false

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
