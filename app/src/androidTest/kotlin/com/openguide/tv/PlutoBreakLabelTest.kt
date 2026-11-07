package com.openguide.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.openguide.tv.data.Channel
import com.openguide.tv.data.Repo
import com.openguide.tv.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hunts for a Pluto channel that's really on an ad break, so it needs internet access and can take
 * several minutes. About one channel in seven is on a break at any moment.
 */
class PlutoBreakLabelTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val repo: Repo get() = (instr.targetContext.applicationContext as OpenGuideApp).repo
    private lateinit var activity: MainActivity

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instr.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    /** The label if it's showing, since this search skips hidden views. Call on the main thread. */
    private fun label(): View? {
        val found = ArrayList<View>()
        activity.window.decorView.findViewsWithText(found, "ad break", View.FIND_VIEWS_WITH_TEXT)
        return found.singleOrNull()
    }

    private fun labelShown() = onMain { label() != null }

    private fun tune(ch: Channel, list: List<Channel>) = onMain {
        MainActivity::class.java.getDeclaredMethod("tuneFullscreen", Channel::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(activity, ch, list)
    }

    private fun launch(crt: Boolean): List<Channel> {
        val ctx = instr.targetContext
        repo.prefs.crtMode = crt
        activity = instr.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val end = SystemClock.uptimeMillis() + 180_000
        while (repo.lineup.value.isEmpty() && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
        return repo.lineup.value.filter { c -> c.streams.size == 1 && "jmp2.uk/plu-" in c.streams[0].url }.shuffled()
    }

    private fun findBreak(list: List<Channel>): Channel {
        for (ch in list.take(80)) {
            tune(ch, list)
            val end = SystemClock.uptimeMillis() + 9_000
            while (SystemClock.uptimeMillis() < end) {
                if (labelShown()) return ch
                SystemClock.sleep(250)
            }
        }
        throw AssertionError("No Pluto channel was on a break")
    }

    private fun screenshot(name: String) {
        instr.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png").close()
        SystemClock.sleep(1500)
    }

    @After fun finish() {
        repo.prefs.crtMode = false
        activity.finish()
        SystemClock.sleep(1000)
    }

    @Test fun labelFollowsTheBreak() {
        val list = launch(crt = false)
        val ch = findBreak(list)
        screenshot("break_normal")

        // Only for full-screen viewing: the guide has its own text around the video.
        instr.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instr.waitForIdleSync()
        assertFalse(labelShown())
        tune(ch, list)
        val back = SystemClock.uptimeMillis() + 5_000
        while (!labelShown() && SystemClock.uptimeMillis() < back) SystemClock.sleep(200)

        // Pluto's breaks have run past four minutes, so allow plenty.
        val end = SystemClock.uptimeMillis() + 480_000
        while (labelShown()) {
            assertTrue("The label never cleared on ${ch.id}", SystemClock.uptimeMillis() < end)
            SystemClock.sleep(1000)
        }
        // Stays off once off: the label used to flicker while the logo was still playing.
        val settled = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < settled) {
            assertFalse("The label came back on ${ch.id}", labelShown())
            SystemClock.sleep(500)
        }
        screenshot("break_over")
    }

    @Test fun labelShowsInCrtModeInsideTheOverscanMargin() {
        val list = launch(crt = true)
        findBreak(list)
        screenshot("break_crt")
        val (right, top) = onMain {
            val v = label()!!
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            (at[0] + v.width) to at[1]
        }
        val metrics = activity.resources.displayMetrics
        assertTrue(right <= metrics.widthPixels - metrics.widthPixels * repo.prefs.crtOverscanX / 100)
        assertTrue(top >= metrics.heightPixels * repo.prefs.crtOverscanY / 100)
    }
}
