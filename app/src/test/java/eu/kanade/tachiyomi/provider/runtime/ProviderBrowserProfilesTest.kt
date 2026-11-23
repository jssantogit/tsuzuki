package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class ProviderBrowserProfilesTest {
    @Test
    fun mangaAllProfileName_matchesPreviouslyShippingSha256Namespace() {
        ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall") shouldBe
            "tsuzuki-provider-0c7fe20bf3b49d4253dfce54"
    }

    @Test
    fun profilesRemainIsolatedBetweenProviderIds() {
        val one = ProviderBrowserProfiles.nameFor("org.example.a")
        val two = ProviderBrowserProfiles.nameFor("org.example.b")
        one shouldBe "tsuzuki-provider-35bc8c964bba2b4a1a894935"
        two shouldBe "tsuzuki-provider-ff5fcffb1162ff90c0abfa8d"
        one shouldNotBe two
    }
}
