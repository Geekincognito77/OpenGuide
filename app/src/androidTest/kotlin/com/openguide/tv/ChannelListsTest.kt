package com.openguide.tv

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.openguide.tv.data.CATEGORY_ALL
import com.openguide.tv.data.CATEGORY_CRT
import com.openguide.tv.data.CATEGORY_FAVORITES
import com.openguide.tv.data.CrtStretch
import com.openguide.tv.data.LANGUAGE_ALL
import com.openguide.tv.data.Repo
import com.openguide.tv.data.matchesLanguage
import com.openguide.tv.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Checks the lineup filters against the real catalog and listings, so it needs internet access. */
class ChannelListsTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val repo: Repo get() = (instr.targetContext.applicationContext as OpenGuideApp).repo
    private lateinit var activity: MainActivity

    private fun waitFor(what: String, timeoutMs: Long = 180_000, ready: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!ready()) {
            assertTrue("Timed out waiting for $what", SystemClock.uptimeMillis() < end)
            SystemClock.sleep(200)
        }
    }

    private fun setLanguage(code: String) {
        repo.setLanguage(code)
        // The lineup is rebuilt just after the preference changes.
        waitFor("language $code", 10_000) {
            repo.lineup.value.isNotEmpty() && repo.lineup.value.all { matchesLanguage(it.languages, code) }
        }
    }

    @Before fun launch() {
        val ctx = instr.targetContext
        activity = instr.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        waitFor("the catalog") { repo.lineup.value.isNotEmpty() }
        repo.prefs.onlyWithListings = false
    }

    @After fun finish() {
        repo.prefs.onlyWithListings = false
        repo.prefs.hideDead = true
        activity.finish()
    }

    @Test fun languageFilterShowsOnlyThatLanguage() {
        setLanguage("spa")
        val spanish = repo.filtered(CATEGORY_ALL)
        assertTrue("Expected Spanish channels", spanish.isNotEmpty())
        assertTrue(spanish.all { it.languages.first() == "spa" })

        setLanguage("eng")
        val english = repo.filtered(CATEGORY_ALL)
        assertTrue(english.all { it.languages.first() == "eng" })
        // Channels iptv-org tags as English but which are Spanish, found by looking at the real data.
        val names = english.map { it.nameLower }
        for (mislabeled in listOf("cine en espanol", "cbs en espanol", "nickelodeon en espanol", "crimenes imperfectos")) {
            assertFalse("$mislabeled is in the English lineup", mislabeled in names)
        }

        setLanguage(LANGUAGE_ALL)
        waitFor("every language", 10_000) { repo.filtered(CATEGORY_ALL).size > english.size }
        setLanguage("eng")
    }

    @Test fun languageCountsMatchWhatTheFilterShows() {
        setLanguage("eng")
        repo.prefs.hideDead = false
        assertEquals(repo.languageCounts()["eng"], repo.filtered(CATEGORY_ALL).size)
    }

    @Test fun onlyWithListingsLeavesNoChannelWithoutOne() {
        waitFor("listings to import", 300_000) { repo.prefs.epgFetchedAt > 0 }
        val before = repo.filtered(CATEGORY_ALL).size
        repo.prefs.onlyWithListings = true
        val listed = repo.filtered(CATEGORY_ALL)
        assertTrue("Expected some listed channels", listed.isNotEmpty())
        assertTrue("Expected some channels to be hidden", listed.size < before)

        // The real check: each one has a programme on right now, so its row can't read "No listings".
        val now = System.currentTimeMillis()
        val empty = listed.filter { ch ->
            repo.db.forChannel(ch.id, now, now + 1).none { now >= it.start && now < it.stop }
        }
        assertEquals("Channels with nothing on: ${empty.take(5).map { it.name }}", 0, empty.size)
    }

    @Test fun deadChannelsAreHiddenExceptFromHandMadeLists() {
        repo.prefs.hideDead = true
        val victim = repo.filtered(CATEGORY_ALL).first()
        val wasFavorite = victim.id in repo.favorites.value
        if (!wasFavorite) repo.toggleFavorite(victim.id)
        repo.setDead(victim.id, true)
        try {
            assertFalse(victim in repo.filtered(CATEGORY_ALL))
            assertTrue(victim in repo.filtered(CATEGORY_FAVORITES))
        } finally {
            repo.setDead(victim.id, false)
            if (!wasFavorite) repo.toggleFavorite(victim.id)
        }
        assertTrue(victim in repo.filtered(CATEGORY_ALL))
    }

    @Test fun crtListWorksLikeFavorites() {
        val pick = repo.filtered(CATEGORY_ALL).take(3)
        repo.crtList.value.toList().forEach { repo.toggleCrt(it) }
        assertTrue(repo.filtered(CATEGORY_CRT).isEmpty())

        pick.forEach { assertTrue(repo.toggleCrt(it.id)) }
        assertEquals(pick, repo.filtered(CATEGORY_CRT))
        assertEquals("It's saved", pick.map { it.id }.toSet(), repo.prefs.crtList)
        assertTrue("Favorites are separate", repo.filtered(CATEGORY_FAVORITES).none { it in pick })

        // A hand-made list keeps what you chose, even with listings required.
        repo.prefs.onlyWithListings = true
        assertEquals(pick, repo.filtered(CATEGORY_CRT))
        repo.prefs.onlyWithListings = false

        assertFalse(repo.toggleCrt(pick[0].id))
        assertEquals(pick.drop(1), repo.filtered(CATEGORY_CRT))
        pick.drop(1).forEach { repo.toggleCrt(it.id) }
    }

    @Test fun pictureAndSubtitlesAreSavedForEachChannel() {
        val (a, b) = repo.filtered(CATEGORY_ALL).take(2).map { it.id }
        val prefs = repo.prefs
        val fallback = prefs.crtStretch
        assertEquals("Uses the default until set", fallback, prefs.channelStretch(a))

        val other = CrtStretch.entries.first { it != fallback }
        prefs.setChannelStretch(a, other)
        prefs.setChannelCrop(a, 2, 12)
        prefs.setSubtitles(a, true)

        assertEquals(other, prefs.channelStretch(a))
        assertEquals(2 to 12, prefs.channelCrop(a))
        assertTrue(prefs.subtitles(a))

        assertEquals("Others are unaffected", fallback, prefs.channelStretch(b))
        assertEquals(0 to 0, prefs.channelCrop(b))
        assertFalse(prefs.subtitles(b))
        prefs.setSubtitles(a, false)
    }
}
