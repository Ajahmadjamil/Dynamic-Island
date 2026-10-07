package com.codewithaj.pixelisland.receivers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.codewithaj.pixelisland.data.IslandSettings
import com.codewithaj.pixelisland.island.IslandActivity
import com.codewithaj.pixelisland.island.IslandStateManager
import kotlinx.coroutines.flow.StateFlow

/**
 * System events that become transient island alerts. Registered dynamically while an island
 * host is running and unregistered when it stops; nothing here wakes the device or polls.
 *
 * Deliberately NOT registered: ACTION_BATTERY_CHANGED (fires on every 1% and temperature
 * change). The level is read once from BatteryManager when it's actually needed.
 */
class SystemEventsMonitor(
    private val context: Context,
    private val state: IslandStateManager,
    private val settings: StateFlow<IslandSettings>,
) {
    private var registered = false
    private val recentBtConnects = HashMap<String, Long>()
    private val btBattery = HashMap<String, Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val s = settings.value
            if (!s.islandEnabled) return
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> if (s.alertCharging) onPowerConnected()
                Intent.ACTION_BATTERY_LOW -> if (s.alertCharging) onBatteryLow()
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    // Registering delivers the current (sticky) value immediately; that's not a change.
                    if (isInitialStickyBroadcast) return
                    if (s.alertRinger) onRingerChanged(intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (s.alertBluetooth) onBluetoothConnected(intent)
                ACTION_BT_BATTERY_LEVEL_CHANGED -> onBluetoothBattery(intent)
                ACTION_WIFI_AP_STATE_CHANGED -> {
                    if (isInitialStickyBroadcast) return
                    when (intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)) {
                        WIFI_AP_STATE_ENABLED -> if (s.alertConnectivity) connectivityAlert(IslandActivity.Connectivity.Kind.HOTSPOT, on = true)
                        WIFI_AP_STATE_DISABLED -> if (s.alertConnectivity) connectivityAlert(IslandActivity.Connectivity.Kind.HOTSPOT, on = false)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Like iOS: the island resets when the screen goes off.
                    state.dismissAlert()
                    state.collapse()
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(ACTION_BT_BATTERY_LEVEL_CHANGED)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
        }
        // EXPORTED because the Bluetooth broadcasts come from the Bluetooth stack's process,
        // not system_server. Safe: every action here is a protected broadcast that only the
        // system can send, so other apps can't spoof alerts.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        registerVpnCallback()
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        try { connectivity?.unregisterNetworkCallback(vpnCallback) } catch (_: Exception) { }
        registered = false
    }

    // ---- VPN --------------------------------------------------------------------------------------
    // A network callback limited to VPN transports: fires only when a VPN comes up or goes down.

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val vpnNetworks = HashSet<Network>()
    /** Registering reports already-connected VPNs; those aren't "changes". */
    private var vpnBaselineDone = false

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val wasConnected = vpnNetworks.isNotEmpty()
            vpnNetworks += network
            if (!wasConnected && vpnBaselineDone) vpnChanged(on = true)
        }

        override fun onLost(network: Network) {
            vpnNetworks -= network
            if (vpnNetworks.isEmpty() && vpnBaselineDone) vpnChanged(on = false)
        }
    }

    private fun registerVpnCallback() {
        val cm = connectivity ?: return
        vpnNetworks.clear()
        vpnBaselineDone = false
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, vpnCallback, main)
            // Callbacks for existing VPNs are posted right after registering; after that, changes.
            main.postDelayed({ vpnBaselineDone = true }, 1_000L)
        } catch (_: Exception) { }
    }

    private fun vpnChanged(on: Boolean) {
        val s = settings.value
        if (s.islandEnabled && s.alertConnectivity) connectivityAlert(IslandActivity.Connectivity.Kind.VPN, on)
    }

    private fun connectivityAlert(kind: IslandActivity.Connectivity.Kind, on: Boolean) {
        state.showAlert(IslandActivity.Connectivity(kind, on, SystemClock.uptimeMillis()), IslandStateManager.ALERT_SHORT_MS)
    }

    // ---- Battery ----------------------------------------------------------------------------------

    private fun batteryLevel(): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 0

    private fun onPowerConnected() {
        // The sticky battery intent tells us the charger type (no receiver is registered).
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        state.showAlert(
            IslandActivity.Charging(
                level = batteryLevel(),
                wireless = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS,
                shownAtMs = SystemClock.uptimeMillis(),
            ),
            IslandStateManager.ALERT_SHORT_MS,
        )
    }

    private fun onBatteryLow() {
        state.showAlert(
            IslandActivity.LowBattery(batteryLevel(), SystemClock.uptimeMillis()),
            IslandStateManager.ALERT_SHORT_MS + 1_000L,
        )
    }

    // ---- Ringer -----------------------------------------------------------------------------------

    private fun onRingerChanged(mode: Int) {
        state.showAlert(IslandActivity.Ringer(mode, SystemClock.uptimeMillis()), IslandStateManager.ALERT_SHORT_MS)
    }

    // ---- Bluetooth ----------------------------------------------------------------------------------

    /**
     * Requires BLUETOOTH_CONNECT on Android 12+ (granted from Islet's settings). Without it the
     * system simply doesn't deliver these broadcasts, and reading the device throws; both are
     * handled by doing nothing.
     */
    @SuppressLint("MissingPermission")
    private fun onBluetoothConnected(intent: Intent) {
        val device = intent.bluetoothDevice() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            // ACL_CONNECTED can fire twice (classic + LE) for one device.
            val last = recentBtConnects[device.address]
            if (last != null && now - last < 8_000L) return
            recentBtConnects[device.address] = now

            val kind = when (device.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> IslandActivity.BluetoothDevice.Kind.HEADPHONES
                BluetoothClass.Device.Major.WEARABLE -> IslandActivity.BluetoothDevice.Kind.WATCH
                else -> return // keyboards, cars' phone links, etc. aren't worth an alert
            }
            val name = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null)
                ?: device.name ?: "Bluetooth device"
            state.showAlert(
                IslandActivity.BluetoothDevice(name, device.address, kind, btBattery[device.address], now),
                IslandStateManager.ALERT_SHORT_MS + 400L,
            )
        } catch (_: SecurityException) {
            // Permission revoked between delivery and read.
        }
    }

    /**
     * Battery level usually arrives a moment after the connection. This action/extra are not in
     * the public SDK, but they're stable framework broadcasts that Settings itself relies on.
     */
    private fun onBluetoothBattery(intent: Intent) {
        val device = intent.bluetoothDevice() ?: return
        val level = intent.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1)
        if (level !in 0..100) return
        val address = try { device.address } catch (_: SecurityException) { return }
        btBattery[address] = level
        state.updateAlert { a ->
            if (a is IslandActivity.BluetoothDevice && a.address == address) a.copy(battery = level) else a
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_BT_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        // Hotspot (WifiManager hidden-but-stable broadcast; Settings and SystemUI use it).
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_ENABLED = 13
    }
}
