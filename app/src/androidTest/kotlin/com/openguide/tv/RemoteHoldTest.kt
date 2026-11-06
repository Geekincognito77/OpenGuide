package com.openguide.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.openguide.tv.ui.GuideView
import com.openguide.tv.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sends the auto-repeating key events a held remote button produces, which `adb input` can't.
 * Needs a device with internet access, because the app loads its real channel list.
 */
class RemoteHoldTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        instr.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun waitFor(what: String, timeoutMs: Long = 180_000, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!onMain(ready)) {
            assertTrue("Timed out waiting for $what", SystemClock.uptimeMillis() < end)
            SystemClock.sleep(200)
        }
    }

    private fun focus(): View? = activity.currentFocus

    private fun press(code: Int) {
        val t = SystemClock.uptimeMillis()
        instr.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        instr.sendKeySync(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        instr.waitForIdleSync()
    }

    /** Key down followed by [repeats] auto-repeat events, as if the button were held, then key up. */
    private fun hold(code: Int, repeats: Int) {
        val t = SystemClock.uptimeMillis()
        for (i in 0..repeats) {
            instr.sendKeySync(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, i))
        }
        instr.sendKeySync(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        instr.waitForIdleSync()
    }

    private fun guide(): GuideView = focus() as GuideView

    @Before fun launch() {
        val ctx = instr.targetContext
        // The last-watched channel would otherwise decide where the guide starts.
        // The background source check would otherwise change the lineup while a test counts rows.
        ctx.getSharedPreferences("openguide", 0).edit().remove("last_channel").putBoolean("hide_dead", false).commit()
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instr.startActivitySync(intent) as MainActivity
        waitFor("the guide") { focus() is GuideView }
    }

    @Test fun holdingDownSpeedsUpThenHoldingUpStopsAtTheTop() {
        val g = onMain { guide() }
        val first = onMain { g.currentChannel }
        assertNotNull(first)

        // 6 repeats at 1 row, 14 at 3, 10 at 6: holding for 30 events crosses 108 rows.
        hold(KeyEvent.KEYCODE_DPAD_DOWN, 29)
        val lineup = (instr.targetContext.applicationContext as OpenGuideApp).repo.filtered("all")
        assertEquals(lineup[108].id, onMain { g.currentChannel?.id })

        hold(KeyEvent.KEYCODE_DPAD_UP, 200)
        assertEquals(first?.id, onMain { g.currentChannel?.id })
        assertTrue("A held Up must stop at the top row, not jump to the tabs", onMain { focus() } is GuideView)

        // A fresh press at the top is how you reach the tabs on a remote with no other route.
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Guide", onMain { (focus() as? TextView)?.text?.toString() })
    }

    @Test fun holdingDownInBrowserKeepsFocusInTheGrid() {
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("a channel card") {
            val parent = focus()?.parent as? RecyclerView
            parent?.layoutManager is GridLayoutManager
        }

        // Faster than the grid can lay out new rows, which is when focus tends to escape a list.
        hold(KeyEvent.KEYCODE_DPAD_DOWN, 150)
        SystemClock.sleep(500)
        val (inGrid, position) = onMain {
            val card = focus()
            val parent = card?.parent as? RecyclerView
            (parent?.layoutManager is GridLayoutManager) to (parent?.getChildAdapterPosition(card) ?: -1)
        }
        assertTrue("Focus left the channel grid", inGrid)
        assertTrue("Holding Down barely moved (card $position)", position > 30)

        press(KeyEvent.KEYCODE_BACK)
        waitFor("the guide again", 5_000) { focus() is GuideView }
    }

    @Test fun holdingOkOpensOptionsAndATapDoesNot() {
        val code = KeyEvent.KEYCODE_DPAD_CENTER
        hold(code, 1)
        waitFor("the options list", 5_000) {
            (focus() as? TextView)?.text?.toString() == "Watch"
        }
        press(KeyEvent.KEYCODE_BACK)
        waitFor("focus back on the guide", 5_000) { focus() is GuideView }

        // A tap goes full screen: the guide is hidden and nothing in it holds focus.
        press(code)
        waitFor("full screen", 5_000) { focus() !is GuideView }
        assertFalse(onMain { (focus() as? TextView)?.text?.toString() == "Watch" })
        press(KeyEvent.KEYCODE_BACK)
        waitFor("focus back on the guide", 5_000) { focus() is GuideView }
    }

    @Test fun channelButtonsChangeChannelAndTheDpadDoesNot() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("full screen", 5_000) { focus() !is GuideView }
        val player = { onMain { (instr.targetContext.applicationContext as OpenGuideApp).repo.prefs.lastChannel } }
        val start = player()
        assertNotNull(start)

        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("The D-pad must not change channel", start, player())

        press(KeyEvent.KEYCODE_CHANNEL_UP)
        val up = player()
        assertTrue("Channel Up must change channel", up != start)
        press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        assertEquals(start, player())

        press(KeyEvent.KEYCODE_BACK)
        waitFor("focus back on the guide", 5_000) { focus() is GuideView }
    }

    @Test fun channelButtonsPageThroughTheGuide() {
        val g = onMain { guide() }
        val first = onMain { g.currentChannel?.id }
        press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        val paged = onMain { g.currentChannel?.id }
        assertTrue("Channel Down must move down the guide", paged != first)
        press(KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(first, onMain { g.currentChannel?.id })
    }

    @Test fun backReturnsToTheChannelBeingWatched() {
        val g = onMain { guide() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) // watch this one in the small window
        val watching = onMain { g.currentChannel?.id }
        assertEquals(watching, onMain { g.tunedChannelId })
        assertTrue(onMain { g.isHome() })

        repeat(8) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(onMain { g.currentChannel?.id } != watching)
        assertFalse(onMain { g.isHome() })

        press(KeyEvent.KEYCODE_BACK)
        assertEquals(watching, onMain { g.currentChannel?.id })
        assertTrue(onMain { g.isHome() })
        assertTrue("Back only moves the guide the first time", onMain { focus() } is GuideView)

        // Once home, Back goes on to the tabs as it always did.
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Guide", onMain { (focus() as? TextView)?.text?.toString() })
    }
}
