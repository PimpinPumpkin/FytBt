package com.fytbt.media

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Surfaces the connected phone's media (over Bluetooth AVRCP) and drives transport controls.
 *
 * Primary source is a [MediaBrowser] bound directly to the system's
 * `BluetoothMediaBrowserService`. That matters for two reasons over the old
 * `getActiveSessions()` approach:
 *   1. It locks onto the *Bluetooth* session specifically, so a local app on the unit
 *      (e.g. Spotify running on the head unit) can't steal Now Playing when the phone pauses.
 *   2. Sending play() through it nudges the A2DP-sink stream handler to request audio focus —
 *      which is what was missing when audio stayed silent until the stock app was opened.
 *
 * Album art over AVRCP arrives as a content:// URI (AvrcpCoverArtProvider), not an inline
 * bitmap, so [TrackMetadata.art] is loaded from the URI off the main thread.
 */
class NowPlayingController(private val appContext: Context) {

    private val msm: MediaSessionManager? =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val listenerComponent = ComponentName(appContext, FytNotificationListener::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // IMPORTANT: do NOT pause the phone on focus loss. This is an A2DP *sink* — the BT stack's
    // own A2dpSinkStreamHandler grabs/releases audio focus as part of normal playback, so a LOSS
    // here usually means "the sink started playing," not "another source took over." Reacting to
    // it by pausing kills the very audio we just started. Source switching is handled by the MCU
    // (SyuBridge.byav.force), not Android audio focus, so this listener stays a no-op.
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* no-op by design */ }

    private val _hasNotificationAccess = MutableStateFlow(false)
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    private val _metadata = MutableStateFlow<TrackMetadata?>(null)
    val metadata: StateFlow<TrackMetadata?> = _metadata.asStateFlow()

    private val _playback = MutableStateFlow<PlaybackInfo?>(null)
    val playback: StateFlow<PlaybackInfo?> = _playback.asStateFlow()

    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    /** Colors pulled from the current album art (null when there's no art / can't extract). */
    private val _artColors = MutableStateFlow<ArtColors?>(null)
    val artColors: StateFlow<ArtColors?> = _artColors.asStateFlow()

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var artJobToken: String? = null   // most-recent art URI we kicked off a load for
    private var lastSourceClaimMs = 0L
    /** True while the activity is in the foreground (between onResume and onStop). */
    private var foreground = false

    // NOTE: the MCU-source ↔ media auto-pause/resume coordination lives in
    // [SourceCoordinatorService] (a foreground service), NOT here — it must survive this Activity
    // being backgrounded/destroyed. This controller is now purely the Now Playing UI source.

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        if (controller == null) attachFromActiveSessions(sessions ?: emptyList())  // fallback display source
    }
    private var fallbackListening = false

    /**
     * Initialize / keep media monitoring alive. Idempotent. Intentionally NOT torn down in onStop —
     * the playback watcher must keep running while the app is backgrounded so it can pause the phone
     * when the user switches to another source (radio/local). Full teardown happens in [shutdown].
     */
    fun start() {
        val freshScope = scope == null
        if (freshScope) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        refreshAccess()
        connectBrowser()
        startFallback()
    }

    /** App went to the background — keep monitoring alive so auto-pause still works. */
    fun onAppBackgrounded() { foreground = false }

    /** Full teardown — call from the activity's onDestroy. */
    fun shutdown() {
        foreground = false
        stopFallback()
        detachController()
        releaseAudioFocus()
        runCatching { browser?.disconnect() }
        browser = null
        scope?.cancel()
        scope = null
    }

    // --- Transport ---------------------------------------------------------
    //
    // Golden rule after much pain: the app NEVER auto-plays. The only time we assert play is when
    // the user explicitly presses play, or when they open the app while the phone is ALREADY
    // playing (to route that audio onto Bluetooth). A manual pause therefore always sticks — we
    // don't fight it. Now-playing state comes straight from the MediaController callback.

    fun play() = claimBtAudio(play = true)
    fun pause() {
        transport { it.pause() }
        releaseAudioFocus()
    }
    fun playPauseToggle() {
        if (_playback.value?.isPlaying == true) pause() else claimBtAudio(play = true)
    }
    fun skipNext() = transport { it.skipToNext() }
    fun skipPrev() = transport { it.skipToPrevious() }
    fun seekTo(positionMs: Long) = transport { it.seekTo(positionMs.coerceAtLeast(0L)) }

    /**
     * Called when our app returns to the foreground. Behaves like the stock BT app: opening it
     * CLAIMS Bluetooth as the active source — killing the radio and pausing on-unit players — but
     * does NOT start playback (play = false): if you just open the app, it silences other sources and
     * leaves the phone where it was; you press play yourself. We only claim when Bluetooth isn't
     * ALREADY the active MCU source (currentAppId != BTAV).
     */
    fun onAppResumed() {
        foreground = true
        if (controller != null && claimOnOpenEnabled() &&
            SourceCoordinatorService.currentAppId != SyuLink.APP_ID_BTAV
        ) {
            claimBtAudio(play = false)
        }
    }

    /** User setting (Settings tab): does opening the app take over audio from other sources? Default on. */
    private fun claimOnOpenEnabled(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLAIM_ON_OPEN, true)

    /**
     * Make Bluetooth the active MCU audio source via the SYU lever (widgetPlayPause), which kills the
     * radio and lets the coordinator pause on-unit players.
     *
     * @param play  true  → land on PLAYING (explicit play button / toggle): assert AVRCP play after
     *                       the lever's toggle settles.
     *              false → just TAKE OVER without starting playback (app-open): tell the coordinator
     *                       not to auto-resume, and assert PAUSE after the toggle so opening the app
     *                       never force-starts a paused phone. Either way a manual pause is respected.
     * Debounced against rapid double-calls (onResume + attachController).
     */
    private fun claimBtAudio(play: Boolean) {
        if (controller == null) return
        if (!play) SourceCoordinatorService.suppressAutoResume = true
        val now = SystemClock.elapsedRealtime()
        if (now - lastSourceClaimMs < CLAIM_DEBOUNCE_MS) {
            transport { if (play) it.play() else it.pause() }
            return
        }
        lastSourceClaimMs = now
        if (play) acquireAudioFocus()
        SyuBridge.switchSourceToBluetooth(appContext)
        scope?.launch {
            delay(700)
            transport { if (play) it.play() else it.pause() }
        }
    }

    /**
     * Hold AUDIOFOCUS_GAIN so the vendor audio path treats media as active and routes the
     * Bluetooth sink stream to the speakers. We keep the request alive while playing; pausing
     * releases it. The focus-change listener is a no-op — we own no PCM stream of our own,
     * we're only flipping the system's "media is playing" state.
     */
    private fun acquireAudioFocus() {
        if (audioFocusRequest != null) return
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        val result = am.requestAudioFocus(req)
        Log.i(TAG, "requestAudioFocus -> $result (1=granted)")
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) audioFocusRequest = req
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let {
            am.abandonAudioFocusRequest(it)
            Log.i(TAG, "abandoned audio focus")
        }
        audioFocusRequest = null
    }

    private inline fun transport(block: (MediaController.TransportControls) -> Unit) {
        val c = controller ?: return
        runCatching { block(c.transportControls) }
    }

    // --- MediaBrowser (primary) -------------------------------------------

    private fun connectBrowser() {
        if (browser != null) return
        val comp = ComponentName(BT_PACKAGE, BT_BROWSER_SERVICE)
        val b = MediaBrowser(appContext, comp, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val token = browser?.sessionToken
                if (token == null) {
                    Log.w(TAG, "browser connected but no session token")
                    return
                }
                Log.i(TAG, "MediaBrowser connected to BluetoothMediaBrowserService")
                attachController(MediaController(appContext, token))
            }
            override fun onConnectionFailed() {
                Log.w(TAG, "MediaBrowser connection to BT service failed; relying on fallback")
            }
            override fun onConnectionSuspended() {
                Log.w(TAG, "MediaBrowser connection suspended")
                detachController()
            }
        }, null)
        browser = b
        runCatching { b.connect() }
            .onFailure { Log.w(TAG, "browser.connect() threw: ${it.message}") }
    }

    // --- getActiveSessions (fallback) -------------------------------------

    private fun startFallback() {
        if (!_hasNotificationAccess.value || fallbackListening) return
        try {
            val current = msm?.getActiveSessions(listenerComponent) ?: emptyList()
            if (controller == null) attachFromActiveSessions(current)
            msm?.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            fallbackListening = true
        } catch (e: SecurityException) {
            _hasNotificationAccess.value = false
        }
    }

    private fun stopFallback() {
        if (fallbackListening) {
            runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
            fallbackListening = false
        }
    }

    private fun attachFromActiveSessions(sessions: List<MediaController>) {
        // Prefer the Bluetooth session; never auto-pick a local-on-unit app.
        val bt = sessions.firstOrNull { it.packageName == BT_PACKAGE }
        if (bt != null) attachController(bt)
    }

    // --- Controller lifecycle ---------------------------------------------

    private fun attachController(c: MediaController) {
        if (controller?.sessionToken == c.sessionToken) {
            captureSnapshot(c)
            return
        }
        detachController()
        controller = c
        Log.i(TAG, "attached MediaController for ${c.packageName}")
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                applyMetadata(metadata)
            }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                _playback.value = state?.toInfo()
            }
            override fun onSessionDestroyed() {
                detachController()
            }
        }
        c.registerCallback(cb, mainHandler)
        controllerCallback = cb
        captureSnapshot(c)
        // The browser connects a few hundred ms after onResume, so onResume's claim ran with no
        // controller yet. If the app is foreground and Bluetooth isn't already the active source,
        // claim it now that the session is live (matches onAppResumed — take over without playing).
        if (foreground && claimOnOpenEnabled() &&
            SourceCoordinatorService.currentAppId != SyuLink.APP_ID_BTAV
        ) claimBtAudio(play = false)
    }

    private fun captureSnapshot(c: MediaController) {
        _hasSession.value = true
        applyMetadata(c.metadata)
        _playback.value = c.playbackState?.toInfo()
    }

    private fun detachController() {
        val c = controller
        val cb = controllerCallback
        if (c != null && cb != null) runCatching { c.unregisterCallback(cb) }
        controller = null
        controllerCallback = null
        artJobToken = null
        _hasSession.value = false
        _metadata.value = null
        _playback.value = null
        _artColors.value = null
    }

    // --- Metadata + art ----------------------------------------------------

    private fun applyMetadata(md: MediaMetadata?) {
        if (md == null) {
            _metadata.value = null
            _artColors.value = null
            return
        }
        val pkg = controller?.packageName
        val inlineArt = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val base = TrackMetadata(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = inlineArt,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            sourcePackage = pkg,
        )
        _metadata.value = base
        updateArtColors(inlineArt)
        if (inlineArt != null) {
            // De-letterbox inline art off-main, then swap it in.
            val title = base.title
            scope?.launch {
                val trimmed = withContext(Dispatchers.Default) { trimBlackBars(inlineArt) }
                if (trimmed !== inlineArt && _metadata.value?.title == title) {
                    _metadata.value = _metadata.value?.copy(art = trimmed)
                    updateArtColors(trimmed)
                }
            }
        }

        // AVRCP cover art usually comes as a content:// URI rather than an inline bitmap.
        if (inlineArt == null) {
            val artUri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (!artUri.isNullOrBlank()) loadArt(artUri, base)
            else fetchOnlineArt(base)  // Spotify/iPhone send no BT art — look it up by title/artist.
        }
    }

    /**
     * Fallback when the phone provides no cover art over Bluetooth: look the album art up online by
     * title + artist (iTunes Search) and apply it. Title-guarded so a slow fetch can't clobber a
     * newer track. Feeds the same Palette + blurred-background pipeline as Bluetooth art.
     */
    private var onlineArtToken: String? = null
    private fun fetchOnlineArt(track: TrackMetadata) {
        val title = track.title?.takeIf { it.isNotBlank() } ?: return
        val key = "${track.artist.orEmpty()}|$title"
        onlineArtToken = key
        scope?.launch {
            val bmp = withContext(Dispatchers.IO) {
                // 1) Disk cache first — works fully offline once we've seen this track before.
                ArtCache.get(appContext, key)?.let { return@withContext it }
                // 2) Otherwise hit the network (only succeeds on WiFi / tethered) and cache the result.
                val url = OnlineArt.lookupArtworkUrl(track.artist, title) ?: return@withContext null
                OnlineArt.download(url)?.also { ArtCache.put(appContext, key, it) }
            }
            if (bmp != null && onlineArtToken == key && _metadata.value?.title == title) {
                _metadata.value = _metadata.value?.copy(art = bmp)
                updateArtColors(bmp)
                Log.i(TAG, "applied art for $title")
            }
        }
    }

    private fun loadArt(uriString: String, forTrack: TrackMetadata) {
        artJobToken = uriString
        scope?.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val decoded = appContext.contentResolver.openInputStream(Uri.parse(uriString)).use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                    decoded?.let { trimBlackBars(it) }
                }.getOrNull()
            }
            // Only apply if this is still the current art and the track hasn't changed underneath us.
            if (bmp != null && artJobToken == uriString && _metadata.value?.title == forTrack.title) {
                _metadata.value = _metadata.value?.copy(art = bmp)
                updateArtColors(bmp)
                Log.i(TAG, "loaded cover art from $uriString")
            } else if (bmp == null) {
                Log.w(TAG, "failed to load cover art from $uriString — trying online")
                fetchOnlineArt(forTrack)
            }
        }
    }

    /**
     * Some sources (e.g. video thumbnails over AVRCP) bake black letterbox bars into a square
     * bitmap. Crop down to the non-black content so we don't show those bars. Sampled scan from
     * each edge; bails out (returns original) if it'd remove too much (mostly-dark artwork).
     */
    private fun trimBlackBars(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return src
        val threshold = 20
        fun luma(p: Int) =
            ((p ushr 16 and 0xFF) * 30 + (p ushr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        val xStep = (w / 40).coerceAtLeast(1)
        val yStep = (h / 40).coerceAtLeast(1)
        fun rowDark(y: Int): Boolean {
            var x = 0
            while (x < w) { if (luma(src.getPixel(x, y)) > threshold) return false; x += xStep }
            return true
        }
        fun colDark(x: Int): Boolean {
            var y = 0
            while (y < h) { if (luma(src.getPixel(x, y)) > threshold) return false; y += yStep }
            return true
        }
        var top = 0; while (top < h && rowDark(top)) top++
        var bottom = h - 1; while (bottom > top && rowDark(bottom)) bottom--
        var left = 0; while (left < w && colDark(left)) left++
        var right = w - 1; while (right > left && colDark(right)) right--
        val nw = right - left + 1
        val nh = bottom - top + 1
        // No bars found, or it'd crop away most of the image — keep the original.
        if (nw == w && nh == h) return src
        if (nw < w / 3 || nh < h / 3) return src
        return runCatching { android.graphics.Bitmap.createBitmap(src, left, top, nw, nh) }.getOrDefault(src)
    }

    /** Extract a palette from the art (off-main) and publish accent/background colors. */
    private fun updateArtColors(bmp: android.graphics.Bitmap?) {
        if (bmp == null) {
            _artColors.value = null
            return
        }
        scope?.launch {
            val colors = withContext(Dispatchers.Default) {
                runCatching {
                    val p = Palette.from(bmp).clearFilters().maximumColorCount(20).generate()
                    val accentSw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
                        ?: p.mutedSwatch
                    val bgSw = p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch
                    if (accentSw == null) null
                    else ArtColors(
                        accent = accentSw.rgb,
                        onAccent = accentSw.bodyTextColor,
                        background = bgSw?.rgb?.let { darken(it, 0.55f) } ?: darken(accentSw.rgb, 0.7f),
                    )
                }.getOrNull()
            }
            _artColors.value = colors
        }
    }

    private fun darken(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * factor
        val g = ((color ushr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    // --- Notification access ----------------------------------------------

    fun refreshAccess(): Boolean {
        val flat = Settings.Secure.getString(
            appContext.contentResolver, "enabled_notification_listeners"
        ) ?: ""
        val granted = flat.split(":").any {
            it.isNotBlank() && ComponentName.unflattenFromString(it)?.packageName == appContext.packageName
        }
        _hasNotificationAccess.value = granted
        return granted
    }

    fun openNotificationAccessSettings() {
        val intent = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    private fun PlaybackState.toInfo() = PlaybackInfo(
        state = state,
        positionMs = position,
        updatedAtElapsedMs = lastPositionUpdateTime,
        playbackSpeed = playbackSpeed,
        actions = actions,
    )

    companion object {
        private const val TAG = "FytBt"
        const val BT_PACKAGE = "com.android.bluetooth"
        const val BT_BROWSER_SERVICE = "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"
        // The SYU source switch is a play/pause toggle; firing it twice quickly cancels out.
        private const val CLAIM_DEBOUNCE_MS = 1500L
        // Shared with MainActivity — "take over audio on open" setting (default on).
        const val PREFS = "fytbt"
        const val KEY_CLAIM_ON_OPEN = "claim_on_open"
    }
}
