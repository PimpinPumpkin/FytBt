package com.fytbt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.fytbt.bt.BluetoothController
import com.fytbt.media.NowPlayingController
import com.fytbt.media.SourceCoordinatorService
import com.fytbt.ui.BluetoothScreen
import com.fytbt.ui.ContactsScreen
import com.fytbt.ui.NowPlayingScreen
import com.fytbt.ui.PhoneScreen
import com.fytbt.ui.RootScreen
import com.fytbt.ui.theme.FytBtTheme
import com.fytbt.ui.theme.NowPlayingDarkTheme
import com.fytbt.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var controller: BluetoothController
    private lateinit var nowPlaying: NowPlayingController
    private val permsGranted = MutableStateFlow(false)

    // User-selectable accent used when there's no album art to derive colors from. Persisted.
    private val prefs by lazy { getSharedPreferences("fytbt", MODE_PRIVATE) }
    private val fallbackAccent = MutableStateFlow(DEFAULT_ACCENT)
    private fun setFallbackAccent(argb: Int) {
        fallbackAccent.value = argb
        prefs.edit().putInt("fallback_accent", argb).apply()
    }

    // "Take over audio when opened" — opening the app claims Bluetooth (kills radio / pauses other
    // players). On by default; the controller reads this pref directly. Persisted.
    private val claimOnOpen = MutableStateFlow(true)
    private fun setClaimOnOpen(enabled: Boolean) {
        claimOnOpen.value = enabled
        prefs.edit().putBoolean(NowPlayingController.KEY_CLAIM_ON_OPEN, enabled).apply()
    }

    // Light / dark / follow-system. Default System so the unit's day/night drives it.
    private val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    // Material You: pull the accent from the device's system colors instead of a fixed swatch.
    private val dynamicColor = MutableStateFlow(false)
    private fun setDynamicColor(enabled: Boolean) {
        dynamicColor.value = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permsGranted.value = permissionsGranted()
        if (permsGranted.value) {
            controller.register(this)
            controller.refreshPaired()
        }
    }

    // --- Dialer / contacts permissions ---
    private val contactsGranted = MutableStateFlow(false)
    private val callLogGranted = MutableStateFlow(false)
    private var pendingDial: String? = null

    private val requestContacts = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { contactsGranted.value = it }

    private val requestCallLog = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { callLogGranted.value = it }

    private val requestCallPhone = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val number = pendingDial
        pendingDial = null
        if (number != null) placeCall(number, allowDirectCall = granted)
    }

    /** Place a call to [number]. With CALL_PHONE we dial directly (routed to the stock app over
     *  HFP); without it we open the dialer prefilled. Outbound call audio/mic is the known-broken
     *  firmware bug — the call connects regardless. */
    private fun dial(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            placeCall(number, allowDirectCall = true)
        } else {
            pendingDial = number
            requestCallPhone.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun placeCall(number: String, allowDirectCall: Boolean) {
        val uri = Uri.fromParts("tel", number, null)
        val action = if (allowDirectCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        // Route straight to the stock SYU Bluetooth phone: it owns the HFP call, shows the in-call
        // UI, and lets you hang up on the unit — and targeting it skips the "how do you want to
        // dial?" chooser (the system dialer route has no UI and forces a hang-up on the phone).
        val stock = Intent(action, uri)
            .setPackage(STOCK_BT_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(stock); true }.getOrDefault(false)) return
        // Fallback if the stock app isn't present: let the system resolve it.
        runCatching { startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun refreshPhonePerms() {
        contactsGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        callLogGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BluetoothController(applicationContext)
        nowPlaying = NowPlayingController(applicationContext)
        // Keep MCU-source auto-pause alive even when our UI is backgrounded/destroyed.
        SourceCoordinatorService.start(applicationContext)
        permsGranted.value = permissionsGranted()
        refreshPhonePerms()
        fallbackAccent.value = prefs.getInt("fallback_accent", DEFAULT_ACCENT)
        claimOnOpen.value = prefs.getBoolean(NowPlayingController.KEY_CLAIM_ON_OPEN, true)
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        dynamicColor.value = prefs.getBoolean("dynamic_color", false)

        setContent {
            val artColors by nowPlaying.artColors.collectAsState()
            val accentArgb by fallbackAccent.collectAsState()
            val mode by themeMode.collectAsState()
            val dynamic by dynamicColor.collectAsState()
            // Whole-app accent = album-art accent if present, else the user's chosen fallback.
            val liveAccent = artColors?.accent ?: accentArgb
            FytBtTheme(accent = liveAccent, themeMode = mode, dynamicColor = dynamic) {
                val adapterEnabled by controller.adapterEnabled.collectAsState()
                val scanMode by controller.scanMode.collectAsState()
                val discoverableUntil by controller.discoverableUntil.collectAsState()
                val paired by controller.paired.collectAsState()
                val a2dpConnected by controller.a2dpSinkConnected.collectAsState()
                val adapterName by controller.adapterName.collectAsState()
                val granted by permsGranted.collectAsState()

                val hasNotificationAccess by nowPlaying.hasNotificationAccess.collectAsState()
                val hasSession by nowPlaying.hasSession.collectAsState()
                val metadata by nowPlaying.metadata.collectAsState()
                val playback by nowPlaying.playback.collectAsState()

                val contactsOk by contactsGranted.collectAsState()
                val callLogOk by callLogGranted.collectAsState()
                val takeOverOnOpen by claimOnOpen.collectAsState()

                LaunchedEffect(Unit) {
                    if (!granted && REQUIRED_PERMISSIONS.isNotEmpty()) requestPerms.launch(REQUIRED_PERMISSIONS)
                }

                RootScreen(
                    artColors = artColors,
                    fallbackAccent = accentArgb,
                    nowPlayingContent = {
                        // The media screen is always dark (art under a scrim / accent-on-black), so
                        // it keeps light text regardless of the app's light/dark setting.
                        NowPlayingDarkTheme(accent = liveAccent) {
                            NowPlayingScreen(
                                hasNotificationAccess = hasNotificationAccess,
                                hasSession = hasSession,
                                metadata = metadata,
                                playback = playback,
                                artColors = artColors,
                                fallbackAccent = accentArgb,
                                onGrantNotificationAccess = { nowPlaying.openNotificationAccessSettings() },
                                onRefreshAccess = {
                                    nowPlaying.refreshAccess()
                                    nowPlaying.start()
                                },
                                onPlayPause = { nowPlaying.playPauseToggle() },
                                onSkipNext = { nowPlaying.skipNext() },
                                onSkipPrev = { nowPlaying.skipPrev() },
                            )
                        }
                    },
                    phoneContent = {
                        PhoneScreen(
                            callLogGranted = callLogOk,
                            contactsGranted = contactsOk,
                            onRequestCallLog = { requestCallLog.launch(Manifest.permission.READ_CALL_LOG) },
                            onDial = { dial(it) },
                        )
                    },
                    contactsContent = {
                        ContactsScreen(
                            contactsGranted = contactsOk,
                            onRequestContacts = { requestContacts.launch(Manifest.permission.READ_CONTACTS) },
                            onDial = { dial(it) },
                        )
                    },
                    bluetoothContent = {
                        BluetoothScreen(
                            adapterEnabled = adapterEnabled,
                            scanMode = scanMode,
                            discoverableUntil = discoverableUntil,
                            paired = paired,
                            a2dpSinkConnected = a2dpConnected,
                            adapterName = adapterName,
                            permissionsGranted = granted,
                            onRequestPermissions = { if (REQUIRED_PERMISSIONS.isNotEmpty()) requestPerms.launch(REQUIRED_PERMISSIONS) },
                            onRequestEnableBluetooth = {
                                runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                            },
                            onMakeDiscoverable = { controller.requestDiscoverable(this@MainActivity) },
                            onStopDiscoverable = { controller.stopDiscoverable() },
                            onSetAdapterName = { controller.setAdapterName(it) },
                            onRefreshPaired = { controller.refreshPaired() },
                            onUnpair = { controller.unpair(it) },
                            onConnect = { controller.connectA2dpSink(it) },
                            fallbackAccent = accentArgb,
                            onPickAccent = { setFallbackAccent(it) },
                            dynamicColor = dynamic,
                            onSetDynamicColor = { setDynamicColor(it) },
                            takeOverOnOpen = takeOverOnOpen,
                            onToggleTakeOverOnOpen = { setClaimOnOpen(it) },
                            themeMode = mode,
                            onSetThemeMode = { setThemeMode(it) },
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        permsGranted.value = permissionsGranted()
        controller.register(this)
        nowPlaying.start()
        if (permsGranted.value) controller.refreshPaired()
    }

    override fun onResume() {
        super.onResume()
        // Notification access may have just been granted in Settings — re-check on resume.
        if (nowPlaying.refreshAccess()) nowPlaying.start()
        // Coming back to our app reclaims the head unit's audio source for Bluetooth
        // (kills the radio, resumes the phone if it was interrupted).
        nowPlaying.onAppResumed()
        refreshPhonePerms()
        if (permsGranted.value) controller.refreshPaired()
    }

    override fun onStop() {
        // Keep NowPlaying's media monitoring alive in the background so it can pause the phone when
        // another source (radio/local) takes over. Only the pairing receiver is torn down here.
        nowPlaying.onAppBackgrounded()
        controller.unregister(this)
        super.onStop()
    }

    override fun onDestroy() {
        nowPlaying.shutdown()
        super.onDestroy()
    }

    private fun permissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    companion object {
        private const val STOCK_BT_PACKAGE = "com.syu.bt"
        private const val DEFAULT_ACCENT = 0xFF7AB7FF.toInt()  // the theme's blue primary
        // API 31+ gates Bluetooth behind runtime permissions; API 29–30 use the legacy
        // BLUETOOTH / BLUETOOTH_ADMIN normal permissions (granted at install), so there's nothing
        // to request at runtime for pairing / discoverable on those.
        private val REQUIRED_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                )
            } else {
                emptyArray()
            }
    }
}
