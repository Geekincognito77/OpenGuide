package com.openguide.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.openguide.tv.data.CATEGORY_ALL
import com.openguide.tv.data.Channel
import com.openguide.tv.data.GUIDE_CHANNEL
import com.openguide.tv.data.GUIDE_CHANNEL_ID
import com.openguide.tv.data.Repo
import com.openguide.tv.ui.GuideView
import com.openguide.tv.ui.MainActivity
import com.openguide.tv.ui.TvPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CRT mode's guide channel and channel-change static. Needs internet access for the real lineup. */
class GuideChannelTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val repo: Repo get() = (instr.targetContext.applicationContext as OpenGuideApp).repo
    private lateinit var activity: MainActivity

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instr.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun waitFor(what: String, timeoutMs: Long = 180_000, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!ready()) {
            assertTrue("Timed out waiting for $what", SystemClock.uptimeMillis() < end)
            SystemClock.sleep(100)
        }
    }

    private fun <T> field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    }

    private fun shows(name: String) = onMain { field<View>(name).visibility == View.VISIBLE }
    private val guideShown get() = shows("guideChannelView")
    private val staticShown get() = shows("staticView")
    private fun player(): TvPlayer = field("player")
    private fun guide(): GuideView = field("guide")

    private fun launch(guideChannel: Boolean = true, static: Boolean = true) {
        val ctx = instr.targetContext
        val prefs = ctx.getSharedPreferences("openguide", 0)
        prefs.edit().remove("last_channel").putBoolean("hide_dead", false)
            .putBoolean("crt_mode", true).putBoolean("guide_channel", guideChannel).putBoolean("channel_static", static).commit()
        start()
    }

    private fun start() {
        val ctx = instr.targetContext
        activity = instr.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        waitFor("the guide") { guide().currentChannel != null }
    }

    private fun press(code: Int) {
        val t = SystemClock.uptimeMillis()
        instr.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        instr.sendKeySync(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        instr.waitForIdleSync()
    }

    private fun tune(ch: Channel) = onMain {
        MainActivity::class.java.getDeclaredMethod("tuneFullscreen", Channel::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(activity, ch, repo.filtered(CATEGORY_ALL).let { listOf(GUIDE_CHANNEL) + it })
    }

    private fun screenshot(name: String) {
        instr.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png").close()
        SystemClock.sleep(1500)
    }

    @After fun finish() {
        val prefs = instr.targetContext.getSharedPreferences("openguide", 0)
        prefs.edit().putBoolean("crt_mode", false).remove("guide_channel").remove("channel_static").remove("last_channel").commit()
        activity.finish()
        SystemClock.sleep(1000)
    }

    @Test fun guideChannelIsChannelZeroAndPlaysNothing() {
        launch()
        onMain { guide().selectChannel(GUIDE_CHANNEL_ID) }
        assertEquals(GUIDE_CHANNEL_ID, onMain { guide().currentChannel?.id })
        assertEquals(0, repo.numbers[GUIDE_CHANNEL_ID])
        screenshot("guide_row")

        tune(GUIDE_CHANNEL)
        assertTrue(guideShown)
        assertEquals(GUIDE_CHANNEL_ID, onMain { player().channel?.id })
        assertFalse("It has no stream to play", onMain { player().exo.isPlaying })
        waitFor("the static to clear", 3_000) { !staticShown }
        waitFor("the listings", 300_000) { repo.prefs.epgFetchedAt > 0 }
        SystemClock.sleep(3_000)
        screenshot("guide_channel")
        SystemClock.sleep(4_000)
        screenshot("guide_channel_later")
        assertFalse("A channel with no stream isn't a dead one", GUIDE_CHANNEL_ID in repo.deadChannels)

        // Hand-picked lists and the catalog don't gain a channel they can't play.
        assertFalse(repo.filtered(CATEGORY_ALL).any { it.id == GUIDE_CHANNEL_ID })
    }

    @Test fun channelButtonsLeaveAndReturn() {
        launch()
        tune(GUIDE_CHANNEL)
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        assertFalse(guideShown)
        assertTrue("Static covers the change", staticShown)
        screenshot("static")
        val real = onMain { player().channel }!!
        assertTrue(real.id != GUIDE_CHANNEL_ID)
        waitFor("the static to clear", 8_000) { !staticShown }

        press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        assertEquals(GUIDE_CHANNEL_ID, onMain { player().channel?.id })
        assertTrue(guideShown)
    }

    @Test fun optionsOnTheGuideChannelDoNotCrash() {
        launch()
        tune(GUIDE_CHANNEL)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_MENU)
        assertFalse(GUIDE_CHANNEL_ID in repo.favorites.value)
    }

    @Test fun comesBackAfterLeavingTheApp() {
        launch()
        tune(GUIDE_CHANNEL)
        onMain { activity.finish() }
        SystemClock.sleep(1500)
        start()
        waitFor("the guide channel", 10_000) { field<TvPlayer?>("player")?.channel?.id == GUIDE_CHANNEL_ID }
    }

    @Test fun bothCanBeTurnedOff() {
        launch(guideChannel = false, static = false)
        waitFor("the channels") { guide().currentChannel != null }
        onMain { guide().selectChannel(GUIDE_CHANNEL_ID) }
        assertTrue(onMain { guide().currentChannel?.id } != GUIDE_CHANNEL_ID)
        tune(repo.filtered(CATEGORY_ALL).first())
        assertFalse(staticShown)
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        assertFalse(staticShown)
        assertFalse(guideShown)
        assertNull(repo.channel(GUIDE_CHANNEL_ID))
    }
}
