package com.fytbt.bt

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice

data class BtDevice(
    val address: String,
    val name: String?,
    val bondState: Int,
    val majorClass: Int,
    val rssi: Int? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address

    val majorClassLabel: String
        get() = when (majorClass) {
            BluetoothClass.Device.Major.PHONE -> "Phone"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
            BluetoothClass.Device.Major.COMPUTER -> "Computer"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
            BluetoothClass.Device.Major.IMAGING -> "Imaging"
            BluetoothClass.Device.Major.TOY -> "Toy"
            BluetoothClass.Device.Major.HEALTH -> "Health"
            BluetoothClass.Device.Major.MISC -> "Misc"
            BluetoothClass.Device.Major.NETWORKING -> "Net"
            BluetoothClass.Device.Major.UNCATEGORIZED -> "Unknown"
            else -> "?"
        }

    companion object {
        fun bondStateLabel(state: Int): String = when (state) {
            BluetoothDevice.BOND_NONE -> "not paired"
            BluetoothDevice.BOND_BONDING -> "pairing…"
            BluetoothDevice.BOND_BONDED -> "paired"
            else -> "?"
        }
    }
}
