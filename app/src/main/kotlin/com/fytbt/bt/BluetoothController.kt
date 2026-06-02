package com.fytbt.bt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wraps the system Bluetooth stack and exposes its state as Compose-friendly StateFlows.
 *
 * Lifecycle: call [register] in onStart and [unregister] in onStop. The controller does not
 * hold an Activity reference, so it survives configuration changes when hoisted in a ViewModel.
 *
 * Hidden-API calls (`removeBond`, A2DP-sink connection state) are best-effort — they catch
 * [ReflectiveOperationException] and fall through quietly so a blocked call doesn't crash.
 */
@SuppressLint("MissingPermission") // permissions are gated through hasConnectPerm()/hasScanPerm()
class BluetoothController(private val appContext: Context) {

    private val manager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? = manager?.adapter

    // isEnabled does not require runtime permission; safe to read pre-grant.
    private val _adapterEnabled = MutableStateFlow(adapter?.isEnabled == true)
    val adapterEnabled: StateFlow<Boolean> = _adapterEnabled.asStateFlow()

    // scanMode requires BLUETOOTH_SCAN — must NOT be read before the user grants it.
    private val _scanMode = MutableStateFlow(BluetoothAdapter.SCAN_MODE_NONE)
    val scanMode: StateFlow<Int> = _scanMode.asStateFlow()

    /** Epoch millis when the current discoverable window ends. null when not discoverable. */
    private val _discoverableUntil = MutableStateFlow<Long?>(null)
    val discoverableUntil: StateFlow<Long?> = _discoverableUntil.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _paired = MutableStateFlow<List<BtDevice>>(emptyList())
    val paired: StateFlow<List<BtDevice>> = _paired.asStateFlow()

    private val _discovered = MutableStateFlow<List<BtDevice>>(emptyList())
    val discovered: StateFlow<List<BtDevice>> = _discovered.asStateFlow()

    /** Connection state per address for the A2DP sink profile — best-effort, may stay empty. */
    private val _a2dpSinkConnected = MutableStateFlow<Set<String>>(emptySet())
    val a2dpSinkConnected: StateFlow<Set<String>> = _a2dpSinkConnected.asStateFlow()

    /** Transient user-facing error from the last scan attempt. Cleared when scan succeeds. */
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    /** The unit's own broadcast name (BluetoothAdapter.name). Requires BLUETOOTH_CONNECT. */
    private val _adapterName = MutableStateFlow<String?>(null)
    val adapterName: StateFlow<String?> = _adapterName.asStateFlow()

    private var registered = false
    private var a2dpSinkProxy: BluetoothProfile? = null
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    _adapterEnabled.value = state == BluetoothAdapter.STATE_ON
                    Log.i(TAG, "ACTION_STATE_CHANGED state=$state enabled=${_adapterEnabled.value}")
                    if (state == BluetoothAdapter.STATE_ON) refreshPaired()
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    val mode = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_SCAN_MODE,
                        BluetoothAdapter.SCAN_MODE_NONE
                    )
                    _scanMode.value = mode
                    val discoverable = mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                    Log.i(TAG, "ACTION_SCAN_MODE_CHANGED mode=$mode discoverable=$discoverable")
                    if (!discoverable) _discoverableUntil.value = null
                    // A successful pair can land while we're in discoverable mode; pull the new bond.
                    refreshPaired()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                    Log.i(TAG, "ACTION_DISCOVERY_STARTED")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                    Log.i(TAG, "ACTION_DISCOVERY_FINISHED found=${_discovered.value.size}")
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.bluetoothDeviceExtra()
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .takeIf { it != Short.MIN_VALUE }?.toInt()
                    if (device != null) {
                        Log.i(TAG, "ACTION_FOUND ${device.address} rssi=$rssi")
                        addDiscovered(device, rssi)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = intent.bluetoothDeviceExtra()
                    val newState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE
                    )
                    val prevState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_NONE
                    )
                    Log.i(TAG, "ACTION_BOND_STATE_CHANGED ${device?.address} $prevState -> $newState")
                    refreshPaired()
                    // bondedDevices is briefly stale after the broadcast on some stacks — retry.
                    scope?.launch {
                        delay(400)
                        refreshPaired()
                    }
                    device?.let { updateDiscoveredBond(it.address, newState) }
                }
            }
        }
    }

    /**
     * Register the BT broadcast receiver and refresh state. Safe to call repeatedly —
     * receiver is only registered once, but state is re-read every call so a post-permission-grant
     * call picks up scanMode/bondedDevices that were unreadable before.
     */
    fun register(context: Context) {
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
            Log.i(TAG, "receiver registered")
        }
        _adapterEnabled.value = adapter?.isEnabled == true
        if (hasScanPerm()) {
            _scanMode.value = runCatching { adapter?.scanMode ?: BluetoothAdapter.SCAN_MODE_NONE }
                .getOrDefault(BluetoothAdapter.SCAN_MODE_NONE)
        }
        refreshAdapterName()
        refreshPaired()
        bindA2dpSinkProxy()
        startForegroundPoll()
    }

    fun refreshAdapterName() {
        if (!hasConnectPerm()) return
        _adapterName.value = runCatching { adapter?.name }.getOrNull()
    }

    /**
     * Set the local Bluetooth name (what other devices see). Public API,
     * needs BLUETOOTH_CONNECT. Truncated by the stack to ~31 chars in practice.
     */
    fun setAdapterName(name: String): Boolean {
        if (!hasConnectPerm()) return false
        val trimmed = name.trim().take(31)
        if (trimmed.isBlank()) return false
        val a = adapter ?: return false
        return try {
            val ok = a.setName(trimmed)
            Log.i(TAG, "setName('$trimmed') -> $ok")
            if (ok) _adapterName.value = trimmed
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "setName denied: ${e.message}")
            false
        }
    }

    fun unregister(context: Context) {
        pollJob?.cancel()
        pollJob = null
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
            Log.i(TAG, "receiver unregistered")
        }
        adapter?.let { a ->
            a2dpSinkProxy?.let { runCatching { a.closeProfileProxy(A2DP_SINK_PROFILE, it) } }
        }
        a2dpSinkProxy = null
        scope?.cancel()
        scope = null
    }

    /**
     * While foreground, poll bondedDevices + scanMode every 2 s. Cheap, and catches the
     * window when the user pairs a phone via the system pair dialog: we might miss the
     * broadcast (we're paused) or read bondedDevices before the bond is committed. Stopping
     * in [unregister] means we don't burn cycles in the background.
     */
    private fun startForegroundPoll() {
        pollJob?.cancel()
        pollJob = scope?.launch {
            while (true) {
                delay(2_000)
                if (hasConnectPerm()) refreshPaired()
                if (hasScanPerm()) {
                    val current = runCatching { adapter?.scanMode ?: BluetoothAdapter.SCAN_MODE_NONE }
                        .getOrDefault(BluetoothAdapter.SCAN_MODE_NONE)
                    if (current != _scanMode.value) _scanMode.value = current
                }
            }
        }
    }

    fun refreshPaired() {
        if (!hasConnectPerm()) {
            _paired.value = emptyList()
            return
        }
        val a = adapter ?: return
        val list = a.bondedDevices?.map { it.toBtDevice() }
            ?.sortedBy { it.displayName.lowercase() } ?: emptyList()
        if (list.size != _paired.value.size || list.map { it.address } != _paired.value.map { it.address }) {
            Log.i(TAG, "paired updated -> ${list.map { "${it.displayName}/${it.address}" }}")
        }
        _paired.value = list
        refreshA2dpSinkState()
    }

    fun startScan(): Boolean {
        if (!hasScanPerm() || !hasConnectPerm()) {
            _scanError.value = "Permission missing — grant Bluetooth permissions."
            Log.w(TAG, "startScan denied: scanPerm=${hasScanPerm()} connectPerm=${hasConnectPerm()}")
            return false
        }
        val a = adapter
        if (a == null) {
            _scanError.value = "No Bluetooth adapter on this device."
            return false
        }
        if (!a.isEnabled) {
            _scanError.value = "Turn Bluetooth on first."
            return false
        }
        _discovered.value = emptyList()
        _scanError.value = null
        if (a.isDiscovering) a.cancelDiscovery()
        val started = a.startDiscovery()
        Log.i(TAG, "startDiscovery() returned $started (scanMode=${_scanMode.value})")
        if (!started) {
            _scanError.value = "Couldn't start scan. Try again in a moment."
        }
        return started
    }

    fun clearScanError() { _scanError.value = null }

    fun stopScan() {
        if (!hasScanPerm()) return
        val a = adapter ?: return
        if (a.isDiscovering) {
            a.cancelDiscovery()
            Log.i(TAG, "stopScan: cancelDiscovery() called")
        }
    }

    /**
     * Fires ACTION_REQUEST_DISCOVERABLE. The OS caps duration at 300s — anything higher is ignored.
     * Returns false if no adapter / no permission. Record window start so UI can run a countdown
     * even before ACTION_SCAN_MODE_CHANGED arrives.
     */
    fun requestDiscoverable(activity: Activity, seconds: Int = DEFAULT_DISCOVERABLE_SECONDS): Boolean {
        if (!hasConnectPerm() || !hasAdvertisePerm()) {
            Log.w(TAG, "requestDiscoverable denied: connect=${hasConnectPerm()} advertise=${hasAdvertisePerm()}")
            return false
        }
        val capped = seconds.coerceAtMost(MAX_DISCOVERABLE_SECONDS)
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, capped)
        return try {
            activity.startActivity(intent)
            _discoverableUntil.value = System.currentTimeMillis() + capped * 1000L
            Log.i(TAG, "requestDiscoverable fired ${capped}s")
            true
        } catch (e: Exception) {
            Log.w(TAG, "requestDiscoverable failed: ${e.message}")
            false
        }
    }

    /**
     * Cancel the active discoverable window early. Tries reflective `setScanMode(SCAN_MODE_CONNECTABLE)`
     * — works on AOSP/UNISOC where the method is exposed without BLUETOOTH_PRIVILEGED. Returns true
     * if the call succeeded (the ACTION_SCAN_MODE_CHANGED broadcast will confirm). Otherwise just
     * clears our local countdown — the OS will still let the existing window expire on its own.
     */
    fun stopDiscoverable(): Boolean {
        val a = adapter ?: return false
        return try {
            val method = a.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
            val result = method.invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE)
            val ok = (result as? Boolean) ?: (result as? Int == 0) // Android 13 may return BluetoothStatusCodes
            Log.i(TAG, "stopDiscoverable setScanMode -> $result (interpreted ok=$ok)")
            if (ok) _discoverableUntil.value = null
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "stopDiscoverable reflection blocked: ${t.javaClass.simpleName}: ${t.message}")
            // We can't actually cancel without privileged access — at minimum drop the countdown
            // so the UI stops showing one. The OS will time out on its own.
            _discoverableUntil.value = null
            false
        }
    }

    fun pair(device: BtDevice): Boolean {
        if (!hasConnectPerm()) return false
        val raw = adapter?.getRemoteDevice(device.address) ?: return false
        return try {
            val started = raw.createBond()
            Log.i(TAG, "createBond(${device.address}) -> $started")
            started
        } catch (e: SecurityException) {
            Log.w(TAG, "createBond denied: ${e.message}")
            false
        }
    }

    /**
     * Hidden-API: BluetoothDevice#removeBond. Reflected; returns false on Android 14+ block,
     * SecurityException, or any other reflective failure. Caller treats false as "unsupported".
     */
    fun unpair(device: BtDevice): Boolean {
        if (!hasConnectPerm()) return false
        val raw = adapter?.getRemoteDevice(device.address) ?: return false
        return try {
            val method = raw.javaClass.getMethod("removeBond")
            val result = (method.invoke(raw) as? Boolean) ?: false
            Log.i(TAG, "removeBond(${device.address}) -> $result")
            result
        } catch (t: Throwable) {
            Log.w(TAG, "removeBond reflection blocked: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Open the A2DP sink profile proxy. Profile constant 11 is hidden but accepted by
     * getProfileProxy; if the system silently rejects it, we just never get a callback
     * and a2dpSinkConnected stays empty.
     */
    private fun bindA2dpSinkProxy() {
        if (!hasConnectPerm()) return
        val a = adapter ?: return
        if (a2dpSinkProxy != null) return
        runCatching {
            a.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == A2DP_SINK_PROFILE) {
                        a2dpSinkProxy = proxy
                        refreshA2dpSinkState()
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == A2DP_SINK_PROFILE) {
                        a2dpSinkProxy = null
                        _a2dpSinkConnected.value = emptySet()
                    }
                }
            }, A2DP_SINK_PROFILE)
        }
    }

    /**
     * Reflectively ask the A2DP sink proxy to (re)connect to a bonded source phone.
     * Hidden API; on AOSP this maps to `BluetoothA2dpSink.connect(BluetoothDevice)`. Returns
     * false if the proxy isn't bound or the call is blocked. The actual connection result
     * arrives later via the existing connection-state callbacks (poll/refreshA2dpSinkState).
     */
    fun connectA2dpSink(device: BtDevice): Boolean {
        if (!hasConnectPerm()) return false
        val proxy = a2dpSinkProxy
        if (proxy == null) {
            Log.w(TAG, "connectA2dpSink: sink proxy not bound")
            return false
        }
        val raw = adapter?.getRemoteDevice(device.address) ?: return false
        return try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val result = method.invoke(proxy, raw)
            val ok = (result as? Boolean) ?: (result as? Int == 0)
            Log.i(TAG, "A2dpSink.connect(${device.address}) -> $result (interpreted ok=$ok)")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "A2dpSink.connect reflection blocked: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun refreshA2dpSinkState() {
        val proxy = a2dpSinkProxy ?: return
        if (!hasConnectPerm()) return
        val connected = try {
            proxy.connectedDevices?.map { it.address }?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        _a2dpSinkConnected.value = connected
    }

    private fun addDiscovered(device: BluetoothDevice, rssi: Int?) {
        val entry = device.toBtDevice(rssi = rssi)
        _discovered.update { current ->
            val without = current.filterNot { it.address == entry.address }
            (without + entry).sortedWith(
                compareByDescending<BtDevice> { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.displayName.lowercase() }
            )
        }
    }

    private fun updateDiscoveredBond(address: String, bondState: Int) {
        _discovered.update { list ->
            list.map { if (it.address == address) it.copy(bondState = bondState) else it }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toBtDevice(rssi: Int? = null) = BtDevice(
        address = address,
        name = try { name } catch (_: SecurityException) { null },
        bondState = try { bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE },
        majorClass = try { bluetoothClass?.majorDeviceClass ?: 0 } catch (_: SecurityException) { 0 },
        rssi = rssi,
    )

    private fun hasConnectPerm(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasScanPerm(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePerm(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "FytBt"
        const val MAX_DISCOVERABLE_SECONDS = 300
        // Default discoverable window we request. OS caps at MAX; 120s is plenty to pair a phone.
        const val DEFAULT_DISCOVERABLE_SECONDS = 120

        // BluetoothProfile.A2DP_SINK is @hide; the int value is stable.
        const val A2DP_SINK_PROFILE = 11
    }
}

private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
