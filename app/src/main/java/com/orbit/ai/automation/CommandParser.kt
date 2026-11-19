package com.orbit.ai.automation

import com.orbit.ai.files.FileCategory
import java.util.Calendar

enum class SettingsTarget { WIFI, BLUETOOTH, DISPLAY, SOUND, LOCATION, BATTERY_SAVER, NOTIFICATIONS, USAGE_ACCESS, MAIN }

sealed interface Command {
    data class OpenApp(val name: String) : Command
    data class Flashlight(val on: Boolean) : Command
    data class Timer(val minutes: Int, val label: String) : Command
    data class Reminder(val atMillis: Long, val text: String) : Command
    data class Remember(val text: String) : Command
    data class OpenSettings(val target: SettingsTarget) : Command
    data object ShowBattery : Command
    data object ShowStorage : Command
    data object OrganizeDownloads : Command
    data class FindByCategory(val category: FileCategory) : Command
    data class FindLarger(val minBytes: Long) : Command
    data object FindDuplicates : Command
    data object FindRecent : Command
    data object LargestVideo : Command
}

/**
 * Rule-based understanding of common phone commands in English and simple Bangla.
 * It is intentionally conservative: if nothing matches clearly, it returns null and the
 * text goes to the AI as a normal question.
 */
object CommandParser {

    private val bnDigits = "০১২৩৪৫৬৭৮৯"

    fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.lowercase().trim()) {
            val d = bnDigits.indexOf(ch)
            sb.append(if (d >= 0) ('0' + d) else ch)
        }
        return sb.toString().replace(Regex("[?!.,।]+$"), "").replace(Regex("\\s+"), " ")
    }

    fun parse(raw: String, now: Calendar = Calendar.getInstance()): Command? {
        val t = normalize(raw)
        if (t.isBlank()) return null

        // Strip the wake word / polite prefixes: "orbit, open youtube", "please open ...".
        val s = t.replace(Regex("^(hey |ok |okay )?(orbit|অরবিট|অর্বিট)[, ]+"), "")
            .replace(Regex("^(please|pls|can you|could you) "), "").trim()

        // ---- remember ----
        Regex("^(remember|মনে রাখো|মনে রেখো)( that)? (.+)$").find(s)?.let {
            return Command.Remember(raw.trim().substring(raw.trim().length - it.groupValues[3].length))
        }

        // Long sentences are questions for the AI, not phone commands ("what is the battery of a tesla?").
        val shortCommand = s.split(" ").size <= 7

        // ---- flashlight ----
        if (shortCommand && Regex("(flash ?light|torch|টর্চ|ফ্ল্যাশলাইট)").containsMatchIn(s)) {
            val off = Regex("(turn off|switch off|off|বন্ধ|নিভাও)").containsMatchIn(s)
            return Command.Flashlight(!off)
        }

        // ---- timers ----
        Regex("(?:timer|টাইমার).*?(\\d+)\\s*(minute|min|hour|hr|মিনিট|ঘণ্টা|ঘন্টা)").find(s)?.let {
            return timer(it.groupValues[1].toInt(), it.groupValues[2], s)
        }
        Regex("(\\d+)\\s*(minute|min|hour|hr|মিনিটের|মিনিট|ঘণ্টার|ঘণ্টা|ঘন্টা)\\s*(?:study |focus )?(?:timer|টাইমার)").find(s)?.let {
            return timer(it.groupValues[1].toInt(), it.groupValues[2], s)
        }

        // ---- reminders ----
        if (Regex("(remind|reminder|রিমাইন্ডার|মনে করিয়ে)").containsMatchIn(s)) {
            return reminder(s, now)
        }

        // ---- settings screens ----
        if (Regex("(setting|সেটিং)").containsMatchIn(s) || s.startsWith("open wi") || s.startsWith("turn on wi")) {
            settingsTarget(s)?.let { return Command.OpenSettings(it) }
        }

        // ---- info ----
        if (shortCommand && Regex("(battery|ব্যাটারি)").containsMatchIn(s)) return Command.ShowBattery
        if (shortCommand && Regex("(storage|space|স্টোরেজ)").containsMatchIn(s) &&
            !Regex("(larger|bigger|largest|duplicate|find|show files)").containsMatchIn(s)
        ) return Command.ShowStorage

        // ---- files ----
        if (Regex("organi[sz]e").containsMatchIn(s) && s.contains("download")) return Command.OrganizeDownloads
        if (s.contains("duplicate")) return Command.FindDuplicates
        if (Regex("(largest|biggest) video").containsMatchIn(s)) return Command.LargestVideo
        Regex("(?:larger|bigger|more) than (\\d+(?:\\.\\d+)?) ?(kb|mb|gb)").find(s)?.let {
            val n = it.groupValues[1].toDouble()
            val mult = when (it.groupValues[2]) { "kb" -> 1024.0; "mb" -> 1024.0 * 1024; else -> 1024.0 * 1024 * 1024 }
            return Command.FindLarger((n * mult).toLong())
        }
        if (Regex("(recently downloaded|recent files|recently modified|latest files)").containsMatchIn(s)) return Command.FindRecent
        if (Regex("^(find|show|list)( all| my)? ").containsMatchIn(s)) {
            categoryFrom(s)?.let { return Command.FindByCategory(it) }
        }

        // ---- open app (after settings, so "open wifi settings" is not an app) ----
        Regex("^(open|launch|start|খোলো|ওপেন)\\s+(.+)$").find(s)?.let {
            val name = it.groupValues[2].trim()
            if (name.split(" ").size <= 4) return Command.OpenApp(name)
        }
        Regex("^(.+?)\\s+(খোলো|খুলো|ওপেন করো|ওপেন কর)$").find(s)?.let {
            return Command.OpenApp(it.groupValues[1].trim())
        }
        return null
    }

    private fun timer(n: Int, unit: String, s: String): Command {
        val minutes = if (unit.startsWith("h") || unit.startsWith("ঘ")) n * 60 else n
        val label = if (s.contains("study") || s.contains("পড়া")) "Study timer" else "Timer"
        return Command.Timer(minutes.coerceIn(1, 24 * 60), label)
    }

    private fun settingsTarget(s: String): SettingsTarget? = when {
        Regex("(wi-?fi|ওয়াইফাই)").containsMatchIn(s) -> SettingsTarget.WIFI
        Regex("(bluetooth|ব্লুটুথ)").containsMatchIn(s) -> SettingsTarget.BLUETOOTH
        Regex("(display|brightness|ডিসপ্লে)").containsMatchIn(s) -> SettingsTarget.DISPLAY
        Regex("(sound|volume|সাউন্ড)").containsMatchIn(s) -> SettingsTarget.SOUND
        Regex("(location|লোকেশন)").containsMatchIn(s) -> SettingsTarget.LOCATION
        Regex("(battery saver|power saving)").containsMatchIn(s) -> SettingsTarget.BATTERY_SAVER
        Regex("(notification|নোটিফিকেশন)").containsMatchIn(s) -> SettingsTarget.NOTIFICATIONS
        Regex("(usage access)").containsMatchIn(s) -> SettingsTarget.USAGE_ACCESS
        s.trim() == "open settings" || s.trim() == "settings" -> SettingsTarget.MAIN
        else -> null
    }

    private fun categoryFrom(s: String): FileCategory? = when {
        Regex("\\bpdfs?\\b").containsMatchIn(s) -> FileCategory.PDF
        Regex("(photo|picture|image)").containsMatchIn(s) -> FileCategory.PHOTOS
        Regex("video").containsMatchIn(s) -> FileCategory.VIDEOS
        Regex("(music|song)").containsMatchIn(s) -> FileCategory.MUSIC
        Regex("audio").containsMatchIn(s) -> FileCategory.AUDIO
        Regex("(apk)").containsMatchIn(s) -> FileCategory.APK
        Regex("(zip|archive)").containsMatchIn(s) -> FileCategory.ARCHIVES
        Regex("(document|docs?\\b)").containsMatchIn(s) -> FileCategory.DOCUMENTS
        else -> null
    }

    /**
     * Supported forms: "in 10 minutes", "in 2 hours", "tomorrow", "tomorrow at 8", "tomorrow at 7:30 pm",
     * "at 6 pm". Anything else falls back to one hour from now so the reminder is never silently lost.
     */
    private fun reminder(s: String, now: Calendar): Command {
        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)

        val text = Regex("(?:to|that|about|for|জন্য)\\s+(.+)$").find(s)?.groupValues?.get(1)
            ?.replace(Regex("\\s+in \\d+\\s*(minutes?|mins?|hours?|hrs?)$"), "")
            ?.replace(Regex("\\s*\\b(tomorrow|today)?\\s*(at \\d{1,2}(:\\d{2})?\\s*(am|pm)?)?$"), "")?.trim().orEmpty()
            .ifBlank { "Reminder" }

        val inMatch = Regex("in (\\d+)\\s*(minute|min|hour|hr)").find(s)
        if (inMatch != null) {
            val n = inMatch.groupValues[1].toInt()
            cal.add(if (inMatch.groupValues[2].startsWith("h")) Calendar.HOUR_OF_DAY else Calendar.MINUTE, n)
            return Command.Reminder(cal.timeInMillis, text)
        }
        val tomorrow = s.contains("tomorrow") || s.contains("আগামীকাল") || s.contains("কাল")
        val at = Regex("at (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(s)
        if (at != null) {
            var h = at.groupValues[1].toInt()
            val m = at.groupValues[2].ifBlank { "0" }.toInt()
            when (at.groupValues[3]) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
            cal.set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23)); cal.set(Calendar.MINUTE, m.coerceIn(0, 59))
            if (tomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
            else if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            return Command.Reminder(cal.timeInMillis, text)
        }
        if (tomorrow) {
            cal.add(Calendar.DAY_OF_YEAR, 1); cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0)
            return Command.Reminder(cal.timeInMillis, text)
        }
        cal.add(Calendar.HOUR_OF_DAY, 1)
        return Command.Reminder(cal.timeInMillis, text)
    }
}
