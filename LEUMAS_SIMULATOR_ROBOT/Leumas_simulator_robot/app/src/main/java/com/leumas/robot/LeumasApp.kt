package com.leumas.robot

import android.app.Application

/**
 * Application entry point.
 *
 * Deliberately holds no BLE state: the BLE stack is lifecycle-scoped so a backgrounded app cannot
 * leak a GATT connection (spec §26).
 */
class LeumasApp : Application()
