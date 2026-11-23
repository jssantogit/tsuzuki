package eu.kanade.tachiyomi.provider.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProviderBrowserProfilesTest {
    @Test
    fun mangaAllProfileName_matchesPreviouslyShippingSha256Namespace() {
        assertEquals(
            "tsuzuki-provider-0c7fe20bf3b49d4253dfce54",
            ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall"),
        )
    }

    @Test
    fun profilesRemainIsolatedBetweenProviderIds() {
        val one = ProviderBrowserProfiles.nameFor("org.example.a")
        val two = ProviderBrowserProfiles.nameFor("org.example.b")
        assertEquals("tsuzuki-provider-35bc8c964bba2b4a1a894935", one)
        assertEquals("tsuzuki-provider-ff5fcffb1162ff90c0abfa8d", two)
        assertNotEquals(one, two)
    }
}
