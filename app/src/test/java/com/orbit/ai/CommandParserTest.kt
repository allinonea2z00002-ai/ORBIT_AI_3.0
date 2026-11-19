package com.orbit.ai

import com.orbit.ai.automation.Command
import com.orbit.ai.automation.CommandParser
import com.orbit.ai.automation.SettingsTarget
import com.orbit.ai.files.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CommandParserTest {

    private fun now() = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 18, 10, 0, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun reminder(text: String): Pair<String, String> {
        val r = CommandParser.parse(text, now()) as Command.Reminder
        val c = Calendar.getInstance().apply { timeInMillis = r.atMillis }
        return "%d-%02d-%02d %02d:%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        ) to r.text
    }

    @Test fun opensApps() {
        assertEquals(Command.OpenApp("youtube"), CommandParser.parse("Open YouTube."))
        assertEquals(Command.OpenApp("facebook"), CommandParser.parse("ORBIT, open facebook"))
    }

    @Test fun settingsAreNotApps() {
        assertEquals(Command.OpenSettings(SettingsTarget.WIFI), CommandParser.parse("Open Wi-Fi settings"))
    }

    @Test fun flashlight() {
        assertEquals(Command.Flashlight(true), CommandParser.parse("Turn on the flashlight"))
        assertEquals(Command.Flashlight(false), CommandParser.parse("turn off the flashlight"))
        assertEquals(Command.Flashlight(true), CommandParser.parse("টর্চ জ্বালাও"))
    }

    @Test fun deviceInfo() {
        assertEquals(Command.ShowBattery, CommandParser.parse("Show battery information"))
        assertEquals(Command.ShowStorage, CommandParser.parse("Tell me my available storage"))
    }

    @Test fun timers() {
        assertEquals(Command.Timer(45, "Study timer"), CommandParser.parse("Start a study timer for 45 minutes"))
        assertEquals(Command.Timer(120, "Timer"), CommandParser.parse("set a timer for 2 hours"))
        assertEquals(Command.Timer(45, "Timer"), CommandParser.parse("৪৫ মিনিট টাইমার"))
    }

    @Test fun fileCommands() {
        assertEquals(Command.FindByCategory(FileCategory.PDF), CommandParser.parse("Find all PDF files"))
        assertEquals(Command.FindLarger(500L * 1024 * 1024), CommandParser.parse("Show files larger than 500 MB"))
        assertEquals(Command.FindDuplicates, CommandParser.parse("Find duplicate photos"))
        assertEquals(Command.LargestVideo, CommandParser.parse("Find my largest video"))
        assertEquals(Command.FindRecent, CommandParser.parse("Find my recently downloaded files"))
        assertEquals(Command.OrganizeDownloads, CommandParser.parse("ORBIT, organize my downloads"))
    }

    @Test fun remindersUseTheGivenClock() {
        assertEquals("2026-11-19 09:00" to "Reminder", reminder("Set a reminder for tomorrow"))
        assertEquals("2026-11-18 10:10" to "drink water", reminder("remind me in 10 minutes to drink water"))
        assertEquals("2026-11-19 08:00" to "call mom", reminder("remind me to call mom tomorrow at 8"))
        assertEquals("2026-11-18 18:00" to "study", reminder("remind me at 6 pm to study"))
    }

    @Test fun rememberKeepsOriginalCase() {
        val c = CommandParser.parse("remember that my exam is on Friday")
        assertTrue(c is Command.Remember)
        assertEquals("my exam is on Friday", (c as Command.Remember).text)
    }

    @Test fun ordinaryQuestionsGoToTheAi() {
        assertNull(CommandParser.parse("what is the battery of a tesla model s and how long does it last"))
        assertNull(CommandParser.parse("open a discussion about the history of rome please"))
        assertNull(CommandParser.parse("explain photosynthesis"))
    }
}
