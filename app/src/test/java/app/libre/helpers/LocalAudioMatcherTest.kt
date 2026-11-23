package app.libre.helpers

import app.libre.extensions.toID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LocalAudioMatcherTest {

    @Before
    fun setUp() {
        LocalAudioMatcher.clearForTesting()
    }

    @After
    fun tearDown() {
        LocalAudioMatcher.clearForTesting()
    }

    @Test
    fun testNormalizeTitle() {
        assertEquals("blinding lights", LocalAudioMatcher.normalizeTitle("01 - Blinding Lights"))
        assertEquals("starboy", LocalAudioMatcher.normalizeTitle("02. Starboy"))
        assertEquals("shape of you", LocalAudioMatcher.normalizeTitle("Shape of You (feat. Someone)"))
        assertEquals("stay", LocalAudioMatcher.normalizeTitle("Stay (ft. Justin Bieber)"))
        assertEquals("cold heart", LocalAudioMatcher.normalizeTitle("Cold Heart (with Dua Lipa)"))
        assertEquals("song title", LocalAudioMatcher.normalizeTitle("Song Title [320kbps] [2024]"))
        assertEquals("track", LocalAudioMatcher.normalizeTitle("Track (Official Audio)"))
        assertEquals("aaruyire", LocalAudioMatcher.normalizeTitle("Aaruyire - MassTamilan.dev"))
    }

    @Test
    fun testExtractSignificantTokens() {
        val tokens = LocalAudioMatcher.extractSignificantTokens("the blinding lights official video")
        assertEquals(setOf("blinding", "lights"), tokens)
    }

    @Test
    fun testExactTitleMatch() {
        LocalAudioMatcher.registerTitlePathForTesting("blinding lights", "/storage/music/blinding_lights.mp3")

        val path = LocalAudioMatcher.getLocalPathByTitle("Blinding Lights")
        assertEquals("/storage/music/blinding_lights.mp3", path)
    }

    @Test
    fun testArtistTitleCombinationMatch() {
        LocalAudioMatcher.registerTitlePathForTesting("the weeknd starboy", "/storage/music/starboy.mp3")

        // Search with separate title and artist
        val path = LocalAudioMatcher.getLocalPathByTitle("Starboy", "The Weeknd")
        assertEquals("/storage/music/starboy.mp3", path)
    }

    @Test
    fun testFuzzyTokenMatch() {
        LocalAudioMatcher.registerTitlePathForTesting(
            "the weeknd starboy official music video",
            "/storage/music/starboy.mp3"
        )

        val path = LocalAudioMatcher.getLocalPathByTitle("Starboy", "The Weeknd")
        assertNotNull(path)
        assertEquals("/storage/music/starboy.mp3", path)
    }

    @Test
    fun testDifferentSongDoesNotMatch() {
        LocalAudioMatcher.registerTitlePathForTesting(
            "the weeknd starboy",
            "/storage/music/starboy.mp3"
        )

        // Same artist, but different song title
        val path = LocalAudioMatcher.getLocalPathByTitle("Save Your Tears", "The Weeknd")
        assertNull(path)
    }

    @Test
    fun testFindPlaylistVideoId() {
        LocalAudioMatcher.registerPlaylistTrackForTesting("blinding lights", "4NRXx6U8ABQ")

        val videoId = LocalAudioMatcher.findPlaylistVideoId("Blinding Lights", "The Weeknd")
        assertEquals("4NRXx6U8ABQ", videoId)
    }

    @Test
    fun testFindPlaylistVideoIdWithMovieName() {
        LocalAudioMatcher.registerPlaylistTrackForTesting("thenmadurai vaigai nadhi", "dQw4w9WgXcQ")

        val videoId = LocalAudioMatcher.findPlaylistVideoId(
            "Thenmadurai Vaigai Nadhi (From \"Dharmathin Thalaivan\")",
            "Malaysia Vasudevan, P. Susheela & S.P. Balasubrahmanyam"
        )
        assertEquals("dQw4w9WgXcQ", videoId)
    }

    @Test
    fun testAreTokensFuzzyEqual() {
        org.junit.Assert.assertTrue(LocalAudioMatcher.areTokensFuzzyEqual("satthiyamaa", "satthiyama"))
        org.junit.Assert.assertTrue(LocalAudioMatcher.areTokensFuzzyEqual("kuththu", "kuthu"))
        org.junit.Assert.assertTrue(LocalAudioMatcher.areTokensFuzzyEqual("nadhi", "nathi"))
        org.junit.Assert.assertFalse(LocalAudioMatcher.areTokensFuzzyEqual("starboy", "blinding"))
    }

    @Test
    fun testFindPlaylistVideoIdTransliteratedSpellingVariation() {
        LocalAudioMatcher.registerPlaylistItemForTesting("7XHm3ukgPME", "Maruthamalai Satthiyama", "Pushpavanam Kuppusamy - Topic")

        // Shazam returns "Maruthamalai Satthiyamaa" by "Pushpavanam K. Kuppusamy"
        val videoId = LocalAudioMatcher.findPlaylistVideoId("Maruthamalai Satthiyamaa", "Pushpavanam K. Kuppusamy")
        assertEquals("7XHm3ukgPME", videoId)
    }

    @Test
    fun testFindPlaylistVideoIdWithMultipleDelimitersAndBrackets() {
        LocalAudioMatcher.registerPlaylistItemForTesting("eyTEr0GQnBM", "Appan Panna (Official Video) | Murugan Songs", "Pushpavanam Kuppusamy - Topic")

        val videoId = LocalAudioMatcher.findPlaylistVideoId("Appan Panna", "Pushpavanam Kuppusamy")
        assertEquals("eyTEr0GQnBM", videoId)
    }

    @Test
    fun testToIDPreservesLocalPath() {
        val path = "/storage/emulated/0/Download/03. Thenmadurai Vaigai Nadhi.mp3"
        assertEquals(path, path.toID())
    }

    @Test
    fun testGetVideoIdFromLocalFilePathEndingWithBrackets() {
        val path = "/storage/emulated/0/Music/Songs/Temp/Tamil Songs/Pongi Varum Kaveri [1989] - Velli Kolusu - [5HPAUsc3ueo].m4a"
        val videoId = LocalAudioMatcher.getVideoId(path)
        assertEquals("5HPAUsc3ueo", videoId)
    }

    @Test
    fun testShareHelperResolvesOriginalUrlForMappedLocalFile() {
        val path = "/storage/emulated/0/Music/Songs/Temp/Tamil Songs/Pongi Varum Kaveri [1989] - Velli Kolusu - [5HPAUsc3ueo].m4a"
        val shareUrl = ShareHelper.getShareUrl(path)
        assertEquals("https://youtu.be/5HPAUsc3ueo", shareUrl)
    }

    @Test
    fun testExtractVideoIdFromFilenameVariants() {
        assertEquals("5HPAUsc3ueo", LocalAudioMatcher.extractVideoIdFromFilename("Song [5HPAUsc3ueo]"))
        assertEquals("5HPAUsc3ueo", LocalAudioMatcher.extractVideoIdFromFilename("Song (5HPAUsc3ueo)"))
        assertEquals("5HPAUsc3ueo", LocalAudioMatcher.extractVideoIdFromFilename("Artist - Song - 5HPAUsc3ueo"))
        assertEquals("jsa:AbCdEf123", LocalAudioMatcher.extractVideoIdFromFilename("Artist - Song [jsa_AbCdEf123]"))
    }

    @Test
    fun testExtractIdFromTextUrlsAndFrames() {
        val ytWithQuery = "prefix https://www.youtube.com/watch?feature=share&v=5HPAUsc3ueo&t=10 suffix"
        assertEquals("5HPAUsc3ueo", LocalAudioMatcher.extractIdFromText(ytWithQuery))

        val ytmWithSi = "prefix https://music.youtube.com/watch?si=123&v=5HPAUsc3ueo suffix"
        assertEquals("5HPAUsc3ueo", LocalAudioMatcher.extractIdFromText(ytmWithSi))

        val jsaSong = "Track metadata https://www.jiosaavn.com/song/tum-hi-ho/Nyo-ZAN2UWY other info"
        assertEquals("jsa:Nyo-ZAN2UWY", LocalAudioMatcher.extractIdFromText(jsaSong))

        val id3Frame = "TAG COMM\u0000\u0000Official Source: https://youtu.be/5HPAUsc3ueo\u0000"
        assertEquals("5HPAUsc3ueo", LocalAudioMatcher.extractIdFromText(id3Frame))
    }
}
