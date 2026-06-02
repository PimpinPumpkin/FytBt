# Keep reflective access to hidden BluetoothDevice / A2dpSink methods we look up by name.
-keepclassmembers class android.bluetooth.BluetoothDevice {
    public *** removeBond();
}
