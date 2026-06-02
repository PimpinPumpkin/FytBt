package com.fytbt.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal client for the SYU vendor IPC (the `com.syu.ms` "toolkit" binder) to read the MCU's
 * current audio source — its `APP_ID`. This is the one signal that reflects EVERY source, including
 * the FM radio and the unit's own players, which bypass Android audio focus entirely.
 *
 * Reverse-engineered from `com.fyt.screenbutton` (it reads APP_ID to highlight the active source):
 *   - bind: Intent("com.syu.ms.toolkit").setPackage("com.syu.ms")  (exported `app.ToolkitService`)
 *   - IRemoteToolkit.getRemoteModule(MODULE_MAIN=0)            (binder txn 1)
 *   - IRemoteModule.register(callback, U_APP_ID=0, enable=1)   (binder txn 3)
 *   - callback IModuleCallback.update(code, ints, flts, strs)  (binder txn 1) → ints[0] = APP_ID
 *
 * APP_ID values (from com.syu.ipc.data.FinalMain): BTAV=3 (Bluetooth audio), RADIO=1, CAR_RADIO=11,
 * THIRD_PLAYER=10 (e.g. Spotify on the unit), AUDIO_PLAYER=8, AUX=5, etc. So APP_ID==3 ⇒ Bluetooth
 * is the active source; anything else ⇒ something else took over.
 *
 * Pure reflection-free AIDL over raw [Binder.transact]; if the bind/transactions ever fail (missing
 * stock app, changed firmware), it just never emits and callers fall back to no source-tracking.
 */
class SyuLink(private val appContext: Context) {

    private val _appId = MutableStateFlow<Int?>(null)
    /** Current MCU audio source (APP_ID), or null until first read / if the IPC is unavailable. */
    val appId: StateFlow<Int?> = _appId.asStateFlow()

    private var bound = false
    @Volatile private var registered = false

    // Our IModuleCallback.Stub — receives update() transactions from the MCU service.
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TXN_UPDATE -> {
                    data.enforceInterface(DESC_CALLBACK)
                    val updateCode = data.readInt()
                    val ints = data.createIntArray()
                    data.createFloatArray()
                    data.createStringArray()
                    if (updateCode == U_APP_ID && ints != null && ints.isNotEmpty()) {
                        if (_appId.value != ints[0]) Log.i(TAG, "MCU APP_ID -> ${ints[0]}")
                        _appId.value = ints[0]
                    }
                    return true
                }
                INTERFACE_TRANSACTION -> { reply?.writeString(DESC_CALLBACK); return true }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) return
            // Binder transactions block — never on the main thread.
            Thread {
                runCatching {
                    val main = getRemoteModule(service, MODULE_MAIN)
                    if (main != null) {
                        registerForUpdate(main, U_APP_ID)
                        registered = true
                        Log.i(TAG, "SyuLink registered for MAIN/APP_ID updates")
                    } else {
                        Log.w(TAG, "SyuLink: getRemoteModule(MAIN) returned null")
                    }
                }.onFailure { Log.w(TAG, "SyuLink register failed: ${it.javaClass.simpleName}: ${it.message}") }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            registered = false
            _appId.value = null
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(ACTION_TOOLKIT).setPackage(PKG_MS)
        bound = runCatching {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "SyuLink bindService threw: ${it.message}"); false
        }
        Log.i(TAG, "SyuLink bind -> $bound")
    }

    fun unbind() {
        if (!bound) return
        runCatching { appContext.unbindService(conn) }
        bound = false
        registered = false
        _appId.value = null
    }

    private fun getRemoteModule(toolkit: IBinder, moduleCode: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC_TOOLKIT)
            data.writeInt(moduleCode)
            toolkit.transact(TXN_GET_MODULE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun registerForUpdate(module: IBinder, updateCode: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESC_MODULE)
            data.writeStrongBinder(callback)
            data.writeInt(updateCode)
            data.writeInt(1) // enable = push current value now + on every change
            module.transact(TXN_REGISTER, data, reply, 1) // matches screenbutton (oneway)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "FytBt"
        private const val PKG_MS = "com.syu.ms"
        private const val ACTION_TOOLKIT = "com.syu.ms.toolkit"
        private const val DESC_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
        private const val DESC_MODULE = "com.syu.ipc.IRemoteModule"
        private const val DESC_CALLBACK = "com.syu.ipc.IModuleCallback"
        private const val TXN_GET_MODULE = 1   // IRemoteToolkit.getRemoteModule
        private const val TXN_REGISTER = 3     // IRemoteModule.register
        private const val TXN_UPDATE = 1       // IModuleCallback.update (incoming)
        private const val INTERFACE_TRANSACTION = 1598968902 // IBinder.INTERFACE_TRANSACTION

        const val MODULE_MAIN = 0              // FinalMainServer.MODULE_CODE_MAIN
        const val U_APP_ID = 0                 // FinalMain.U_APP_ID (update index of the source)
        const val APP_ID_BTAV = 3              // FinalMain.APP_ID_BTAV — Bluetooth audio is active
    }
}
