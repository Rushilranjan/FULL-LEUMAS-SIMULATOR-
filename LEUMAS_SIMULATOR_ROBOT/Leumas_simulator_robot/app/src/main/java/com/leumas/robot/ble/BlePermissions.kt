package com.leumas.robot.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permission handling.
 *
 * minSdk is 31, so only the modern split applies: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. The
 * manifest declares `neverForLocation` on the scan permission, which means `ACCESS_FINE_LOCATION`
 * is not needed and — importantly — the legacy "location services must be switched on to scan"
 * trap does not apply either. That trap is the single most common cause of a scan that returns
 * nothing with no error on API 23–30 devices.
 */
object BlePermissions {

    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun allGranted(context: Context): Boolean = REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(context: Context): List<String> = REQUIRED.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothOn(context: Context): Boolean = adapter(context)?.isEnabled == true
}
