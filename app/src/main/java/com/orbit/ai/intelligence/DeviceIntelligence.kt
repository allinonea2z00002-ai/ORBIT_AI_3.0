package com.orbit.ai.intelligence

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes get() = totalBytes - freeBytes
    val usedFraction get() = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes
}

data class BatteryInfo(
    val percent: Int,
    val charging: Boolean,
    val chargeSource: String,
    val temperatureC: Float?,
    val health: String,
    val voltageMv: Int?
)

data class DeviceInfo(
    val rows: List<Pair<String, String>>
)

data class AppEntry(val label: String, val packageName: String, val category: String)
data class AppUsage(val label: String, val packageName: String, val foregroundMs: Long)

/**
 * Reads only what Android exposes to normal apps. Anything unavailable is reported as such,
 * never guessed. Battery health is the coarse value Android gives (good, overheat, ...); exact
 * capacity percentage is not available to apps.
 */
class DeviceIntelligence(private val context: Context) {

    private val pm get() = context.packageManager

    fun storage(): StorageInfo {
        val s = StatFs(Environment.getDataDirectory().path)
        return StorageInfo(s.totalBytes, s.availableBytes)
    }

    fun battery(): BatteryInfo {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return BatteryInfo(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            chargeSource = when {
                plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC charger"
                plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
                plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Wireless"
                else -> "Not plugged in"
            },
            temperatureC = if (temp == Int.MIN_VALUE) null else temp / 10f,
            health = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                else -> "Not reported"
            },
            voltageMv = if (volt > 0) volt else null
        )
    }

    fun device(): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        val rows = listOf(
            "Model" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "RAM" to "${mi.totalMem / 1_048_576} MB total, ${mi.availMem / 1_048_576} MB free now",
            "CPU" to "${Build.HARDWARE}, ${Runtime.getRuntime().availableProcessors()} cores, ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
            "Display" to "${dm.widthPixels} x ${dm.heightPixels} px, ${dm.densityDpi} dpi",
            "Network" to networkType(),
            "Sensors" to "${sensors.size} available" +
                (if (sensors.isNotEmpty()) " (e.g. " + sensors.take(5).joinToString { it.name } + ")" else "")
        )
        return DeviceInfo(rows)
    }

    private fun networkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "Offline") ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    /** Apps that have a launcher icon (this is what Android lets us see without a special permission). */
    suspend fun launchableApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    category = categoryName(it.activityInfo.applicationInfo.category)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun categoryName(c: Int): String = when (c) {
        ApplicationInfo.CATEGORY_GAME -> "Games"
        ApplicationInfo.CATEGORY_AUDIO -> "Audio"
        ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_IMAGE -> "Images"
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_MAPS -> "Maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
        else -> "Other"
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Foreground time per app over the last [days] days. Empty if Usage Access is not granted. */
    suspend fun usage(days: Int = 7): List<AppUsage> = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) return@withContext emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(end - days * 86_400_000L, end)
        val launchable = launchableApps().associateBy { it.packageName }
        stats.values
            .filter { it.totalTimeInForeground > 60_000 && launchable.containsKey(it.packageName) }
            .map { AppUsage(launchable[it.packageName]!!.label, it.packageName, it.totalTimeInForeground) }
            .sortedByDescending { it.foregroundMs }
    }

    fun launchIntentFor(packageName: String): Intent? =
        pm.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
