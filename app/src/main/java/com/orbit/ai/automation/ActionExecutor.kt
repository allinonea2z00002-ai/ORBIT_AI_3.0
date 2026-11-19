package com.orbit.ai.automation

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.orbit.ai.database.AutomationDao
import com.orbit.ai.database.AutomationLogEntity
import com.orbit.ai.files.formatBytes
import com.orbit.ai.intelligence.DeviceIntelligence
import java.text.DateFormat
import java.util.Date

/** What happened when a command ran. File commands are answered by the files module, so they are flagged. */
sealed interface ActionOutcome {
    data class Done(val message: String, val success: Boolean = true) : ActionOutcome
    /** The command needs the file index; the caller handles it. */
    data class NeedsFiles(val command: Command) : ActionOutcome
    data class Remembered(val text: String) : ActionOutcome
}

/**
 * Runs parsed commands through normal Android APIs and intents only.
 * Restricted actions (Wi-Fi, Bluetooth, location...) open the system settings page for the user
 * to finish; ORBIT never bypasses Android's security model.
 */
class ActionExecutor(
    private val context: Context,
    private val log: AutomationDao,
    private val intel: DeviceIntelligence
) {
    private var torchOn = false

    suspend fun execute(commandText: String, command: Command): ActionOutcome {
        val outcome = perform(command)
        val (msg, ok) = when (outcome) {
            is ActionOutcome.Done -> outcome.message to outcome.success
            is ActionOutcome.NeedsFiles -> "File request" to true
            is ActionOutcome.Remembered -> "Saved to memory" to true
        }
        log.insertLog(AutomationLogEntity(command = commandText, result = msg, success = ok))
        return outcome
    }

    private suspend fun perform(c: Command): ActionOutcome = when (c) {
        is Command.OpenApp -> openApp(c.name)
        is Command.Flashlight -> flashlight(c.on)
        is Command.Timer -> timer(c)
        is Command.Reminder -> reminder(c)
        is Command.Remember -> ActionOutcome.Remembered(c.text)
        is Command.OpenSettings -> openSettings(c.target)
        Command.ShowBattery -> battery()
        Command.ShowStorage -> storage()
        Command.OrganizeDownloads, is Command.FindByCategory, is Command.FindLarger,
        Command.FindDuplicates, Command.FindRecent, Command.LargestVideo -> ActionOutcome.NeedsFiles(c)
    }

    // ---------- apps ----------

    private suspend fun openApp(name: String): ActionOutcome {
        val wanted = name.lowercase().replace(Regex("^the "), "").replace(" app", "").trim()
        val apps = intel.launchableApps()
        val hit = apps.firstOrNull { it.label.lowercase() == wanted }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(wanted) }
            ?: apps.firstOrNull { it.label.lowercase().contains(wanted) }
            ?: return ActionOutcome.Done("I could not find an app called \"$name\" on this phone.", false)
        val intent = intel.launchIntentFor(hit.packageName)
            ?: return ActionOutcome.Done("${hit.label} cannot be launched.", false)
        return start(intent, "Opening ${hit.label}.")
    }

    // ---------- flashlight ----------

    private fun flashlight(on: Boolean): ActionOutcome {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ActionOutcome.Done("This phone has no flashlight I can control.", false)
        return try {
            cm.setTorchMode(id, on)
            torchOn = on
            ActionOutcome.Done(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            ActionOutcome.Done("I could not switch the flashlight: ${e.message}", false)
        }
    }

    // ---------- timer / reminder ----------

    private fun timer(c: Command.Timer): ActionOutcome {
        // Preferred: the phone's Clock app, which is exact even in battery saver.
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, c.minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, c.label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            return start(intent, "${c.label} set for ${c.minutes} minutes in your Clock app.")
        }
        // Fallback: our own notification timer (may be late in battery saver).
        ReminderWorker.scheduleOnce(context, System.currentTimeMillis() + c.minutes * 60_000L, c.label, "${c.label} finished.")
        return ActionOutcome.Done("${c.label} set for ${c.minutes} minutes. It may be a little late if battery saver is on." + notifWarning())
    }

    private fun reminder(c: Command.Reminder): ActionOutcome {
        ReminderWorker.scheduleOnce(context, c.atMillis, "ORBIT reminder", c.text)
        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.atMillis))
        return ActionOutcome.Done("Reminder set for $whenText: ${c.text}." + notifWarning())
    }

    private fun notifWarning(): String =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) ""
        else " Notifications are blocked for ORBIT, so turn them on in system settings or it cannot alert you."

    // ---------- settings ----------

    private fun openSettings(t: SettingsTarget): ActionOutcome {
        val (action, label) = when (t) {
            SettingsTarget.WIFI -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi settings"
            SettingsTarget.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
            SettingsTarget.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS to "display settings"
            SettingsTarget.SOUND -> Settings.ACTION_SOUND_SETTINGS to "sound settings"
            SettingsTarget.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location settings"
            SettingsTarget.BATTERY_SAVER -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "battery saver settings"
            SettingsTarget.NOTIFICATIONS -> Settings.ACTION_APP_NOTIFICATION_SETTINGS to "notification settings"
            SettingsTarget.USAGE_ACCESS -> Settings.ACTION_USAGE_ACCESS_SETTINGS to "usage access settings"
            SettingsTarget.MAIN -> Settings.ACTION_SETTINGS to "settings"
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (t == SettingsTarget.NOTIFICATIONS) intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        return start(intent, "Opening $label. Android needs you to make the change there.")
    }

    // ---------- info ----------

    private fun battery(): ActionOutcome {
        val b = intel.battery()
        val pct = if (b.percent >= 0) "${b.percent}%" else "unknown"
        val chg = if (b.charging) "charging (${b.chargeSource})" else "not charging"
        val temp = b.temperatureC?.let { ", ${"%.1f".format(it)} °C" } ?: ""
        return ActionOutcome.Done("Battery is $pct, $chg$temp. Health reported by Android: ${b.health}.")
    }

    private fun storage(): ActionOutcome {
        val s = intel.storage()
        return ActionOutcome.Done(
            "${formatBytes(s.freeBytes)} free of ${formatBytes(s.totalBytes)} (${(s.usedFraction * 100).toInt()}% used)."
        )
    }

    private fun start(intent: Intent, success: String): ActionOutcome = try {
        context.startActivity(intent)
        ActionOutcome.Done(success)
    } catch (e: Exception) {
        ActionOutcome.Done("Android could not open that: ${e.message}", false)
    }
}
