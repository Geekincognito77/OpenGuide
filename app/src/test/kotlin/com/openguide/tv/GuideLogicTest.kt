package com.openguide.tv

import com.openguide.tv.data.*
import org.junit.Assert.*
import org.junit.Test

class GuideLogicTest {
    private fun prog(start: Long, stop: Long, t: String = "p") = Programme(start, stop, t, null, null, null, null)
    private fun stream(url: String, q: String? = null, geo: Boolean = false, off: Boolean = false) =
        StreamInfo(url, q, geo, off, null, null)

    @Test fun xmltvTimeHonoursOffset() {
        assertEquals(1793980620000L, parseXmltvTime("20261106155700 +0000"))
        assertEquals(1793980620000L, parseXmltvTime("20261106105700 -0500"))
        assertEquals(1793980620000L, parseXmltvTime("20261106212700 +0530"))
    }

    @Test fun xmltvTimeWithoutOffsetIsUtc() {
        assertEquals(1793980620000L, parseXmltvTime("20261106155700"))
    }

    @Test fun xmltvTimeRejectsJunk() {
        assertNull(parseXmltvTime(null))
        assertNull(parseXmltvTime("soon"))
        assertNull(parseXmltvTime("abcd1106155700"))
    }

    @Test fun namesMatchAcrossSpellings() {
        assertEquals(normalizeName("FOX Weather"), normalizeName("Fox Weather HD"))
        assertEquals(normalizeName("CNN"), normalizeName("CNN (East)"))
        assertEquals("перви", normalizeName("Перви"))
        assertEquals("", normalizeName(null))
    }

    @Test fun blocksFillGapsAndCoverWholeRange() {
        val b = buildBlocks(listOf(prog(10, 20), prog(30, 40)), 0, 50)
        assertEquals(listOf(0L to 10L, 10L to 20L, 20L to 30L, 30L to 40L, 40L to 50L), b.map { it.start to it.stop })
        assertEquals(listOf(false, true, false, true, false), b.map { it.programme != null })
    }

    @Test fun blocksTrimOverlapsAndClipToRange() {
        val b = buildBlocks(listOf(prog(0, 15), prog(10, 30), prog(25, 70)), 5, 50)
        assertEquals(listOf(5L to 15L, 15L to 30L, 30L to 50L), b.map { it.start to it.stop })
    }

    @Test fun emptyRowIsOneEmptyBlock() {
        assertEquals(listOf(Block(0, 100, null)), buildBlocks(emptyList(), 0, 100))
    }

    @Test fun exactIdBeatsNameMatch() {
        val r = resolveXmlChannels(
            xml = linkedMapOf("a" to listOf("Fox"), "b" to listOf("Something")),
            direct = mapOf("b" to listOf("Fox.us")),
            aliases = mapOf("fox" to listOf("Fox.us")),
            alreadyClaimed = emptySet(),
        )
        assertEquals(mapOf("b" to listOf("Fox.us")), r)
    }

    @Test fun affiliatesCannotStackOnOneChannel() {
        val r = resolveXmlChannels(
            xml = linkedMapOf("wabc" to listOf("ABC"), "kabc" to listOf("ABC HD")),
            direct = emptyMap(),
            aliases = mapOf("abc" to listOf("ABC.us")),
            alreadyClaimed = emptySet(),
        )
        assertEquals(mapOf("wabc" to listOf("ABC.us")), r)
    }

    @Test fun claimedChannelsStayWithEarlierSource() {
        val r = resolveXmlChannels(linkedMapOf("x" to listOf("ABC")), emptyMap(), mapOf("abc" to listOf("ABC.us")), setOf("ABC.us"))
        assertTrue(r.isEmpty())
    }

    @Test fun holdingSpeedsUpButSinglePressesMoveOneRow() {
        assertEquals(1, rowStep(0))
        assertEquals(1, rowStep(5))
        assertEquals(3, rowStep(6))
        assertEquals(6, rowStep(20))
    }

    @Test fun streamsOrderedBestFirst() {
        val low = stream("low", "480p")
        val high = stream("high", "1080p")
        val geo = stream("geo", "1080p", geo = true)
        val off = stream("off", "720p", off = true)
        assertEquals(listOf("high", "low", "off", "geo"), orderStreams(listOf(geo, off, low, high), null).map { it.url })
        assertEquals("geo", orderStreams(listOf(low, high, geo), "geo").first().url)
    }

    @Test fun languagesPreferFeedsThatHaveStreams() {
        val feeds = listOf(
            FeedLanguages("SD", true, listOf("eng")),
            FeedLanguages("Latin", false, listOf("spa")),
        )
        assertEquals(listOf("spa"), taggedLanguages(feeds, setOf("Latin")))
        assertEquals(listOf("eng", "spa"), taggedLanguages(feeds, setOf("SD", "Latin")))
        assertEquals(listOf("eng"), taggedLanguages(feeds, setOf(null)))
        assertEquals(emptyList<String>(), taggedLanguages(emptyList(), setOf(null)))
    }

    @Test fun namesOverruleAnEnglishTag() {
        val eng = listOf("eng")
        assertEquals(listOf("spa"), correctLanguages(listOf("Cine en espanol"), eng))
        assertEquals(listOf("spa"), correctLanguages(listOf("CBS en español"), eng))
        assertEquals(listOf("spa"), correctLanguages(listOf("Crimenes Imperfectos", "Crímenes imperfectos"), eng))
        assertEquals(listOf("spa"), correctLanguages(listOf("Pluto TV Terror"), eng))
        assertEquals(listOf("por"), correctLanguages(listOf("Filmes de Acao"), eng))
    }

    @Test fun namesLeaveOrdinaryChannelsAlone() {
        val eng = listOf("eng")
        for (name in listOf("BET Cinema", "70s Cinema", "Cinemax Classics", "ToonGoggles", "CNN")) {
            assertEquals(name, eng, correctLanguages(listOf(name), eng))
        }
        // Only an English tag is treated as suspect.
        assertEquals(listOf("fra"), correctLanguages(listOf("Cine en espanol"), listOf("fra")))
        assertEquals(emptyList<String>(), correctLanguages(listOf("Cine"), emptyList()))
    }

    @Test fun languageFilterOnlyCountsTheMainLanguage() {
        assertTrue(matchesLanguage(listOf("eng", "spa"), "eng"))
        assertFalse(matchesLanguage(listOf("eng", "spa"), "spa"))
        assertFalse(matchesLanguage(emptyList(), "eng"))
        assertTrue(matchesLanguage(emptyList(), LANGUAGE_ALL))
    }

    @Test fun probeTellsWorkingFromBroken() {
        assertEquals(true, probeVerdict(200, "/live/index.m3u8", "#EXTM3U\n#EXT"))
        assertEquals(true, probeVerdict(200, "/live/index.php", "\uFEFF#EXTM3U"))
        assertEquals(false, probeVerdict(200, "/live/index.m3u8", "<html>Not here"))
        assertEquals(false, probeVerdict(404, "/live/index.m3u8", ""))
        assertEquals(false, probeVerdict(403, "/live/index.m3u8", ""))
        assertEquals(true, probeVerdict(200, "/video.MP4", ""))
        assertNull(probeVerdict(429, "/live/index.m3u8", ""))
    }

    @Test fun widescreenOnASqueezedTubeIsLetterboxed() {
        // A 16:9 frame squeezed onto a 4:3 tube: 16:9 video fills the width and 3/4 of the height.
        assertEquals(1920 to 810, crtVideoSize(1920, 1080, 4f / 3f, 16f / 9f, CrtStretch.FIT))
        // Filling crops the sides instead, so it overflows the frame's width.
        assertEquals(2560 to 1080, crtVideoSize(1920, 1080, 4f / 3f, 16f / 9f, CrtStretch.FILL))
    }

    @Test fun fourByThreeFillsASqueezedTube() {
        assertEquals(1920 to 1080, crtVideoSize(1920, 1080, 4f / 3f, 4f / 3f, CrtStretch.FIT))
        assertEquals(1920 to 1080, crtVideoSize(1920, 1080, 4f / 3f, 4f / 3f, CrtStretch.FILL))
    }

    @Test fun tallVideoIsPillarboxed() {
        assertEquals(1080 to 1080, crtVideoSize(1440, 1080, 4f / 3f, 1f, CrtStretch.FIT))
        assertEquals(1440 to 1440, crtVideoSize(1440, 1080, 4f / 3f, 1f, CrtStretch.FILL))
    }

    @Test fun stretchIgnoresShape() {
        assertEquals(1920 to 1080, crtVideoSize(1920, 1080, 4f / 3f, 2.35f, CrtStretch.STRETCH))
    }

    @Test fun unsqueezedSignalKeepsTheFramesShape() {
        assertEquals(1920 to 1080, crtVideoSize(1920, 1080, 16f / 9f, 16f / 9f, CrtStretch.FIT))
        assertEquals(1440 to 1080, crtVideoSize(1920, 1080, 16f / 9f, 4f / 3f, CrtStretch.FIT))
    }

    @Test fun recognisesPlutosBreakFillerByName() {
        // Real segment paths, including the different ways Pluto spells it.
        assertTrue(isBreakFiller("https://x.plutotv.net/410_Pluto_TV_OandO/clip/58e5377d_ptv_7424_ad_bumper_animation_dots_normal_10_1/1080pDRM/hls/hls_2400-00000.ts"))
        assertTrue(isBreakFiller("https://x.plutotv.net/701_pluto_tv_oando/clip/58e5397b_ptv-7424_ad_bumper_animation_dots_normal_30_1/1080pDRM/seg_1.ts"))
        assertTrue(isBreakFiller("https://x.plutotv.net/443_pluto_tv_oando/clip/58e5371a_ptv-7424adbumperanimationdotsinverted30_30fps/1080pDRM/seg_1.ts"))
        assertFalse(isBreakFiller("https://x.plutotv.net/316_pluto/clip/61163aa1_walkertexasranger_141/720pDRM/hls/hls_600-00033.ts"))
        assertFalse(isBreakFiller("https://x.plutotv.net/v1/mp4/c(ts)/p(0_ad%2Fcreative%2F67eeff33_ad%2F720p%2Fvideo_600.mp4)/1.ts"))
        // A show that merely has the word in its title.
        assertFalse(isBreakFiller("https://x.plutotv.net/1_pluto/clip/abc_Road_Bumper_Cars_S1E2/720p/hls/hls_600-00001.ts"))
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(isNewerVersion("v2", "1.9.9"))
        assertTrue(isNewerVersion("1.1.1", "1.1"))
        assertFalse(isNewerVersion("v1.1.0", "1.1.0"))
        assertFalse(isNewerVersion("1.1", "1.1.0"))
        assertFalse(isNewerVersion("1.0.9", "1.1.0"))
    }

    @Test fun croppingBarsLetsThePictureFillTheScreen() {
        // A 2.35:1 film baked into a 16:9 frame has about 12% bars top and bottom.
        val shape = croppedAspect(16f / 9f, 0, 12)
        assertEquals(2.34f, shape, 0.01f)
        // Stretched, the part left after cropping fills the frame, so the whole video draws larger.
        val fitted = crtVideoSize(1920, 1080, 4f / 3f, shape, CrtStretch.STRETCH)
        assertEquals(1920 to 1080, fitted)
        assertEquals(1920 to 1421, uncroppedSize(fitted.first, fitted.second, 0, 12))
    }

    @Test fun noCropChangesNothing() {
        assertEquals(16f / 9f, croppedAspect(16f / 9f, 0, 0), 0.0001f)
        assertEquals(1920 to 1080, uncroppedSize(1920, 1080, 0, 0))
    }

    @Test fun savedCropIsReadSafely() {
        assertEquals(3 to 12, parseCrop("3,12"))
        assertEquals(0 to 0, parseCrop(null))
        assertEquals(0 to 0, parseCrop("junk"))
        assertEquals(0 to 0, parseCrop("3"))
        assertEquals(MAX_CROP to 0, parseCrop("99,-4"))
    }
}
