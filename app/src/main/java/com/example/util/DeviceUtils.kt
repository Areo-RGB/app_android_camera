package com.example.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

object DeviceUtils {

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    fun getLocalIpAddress(context: Context): String {
        try {
            // First check Wi-Fi manager
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                val ip = String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }

            // Fallback to iterating network interfaces (works for Wi-Fi hotspot / AP mode too!)
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Ignore loopback
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (!hostAddress.contains(":")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val batteryPct = if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            100
        }
        return Pair(batteryPct, isCharging)
    }

    fun getAvailableStorageGb(): Double {
        return try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val gb = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "%.1f", gb).toDouble()
        } catch (_: Exception) {
            0.0
        }
    }
}
