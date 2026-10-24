package eu.kanade.tachiyomi.ui.tsuzuki.profile

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.home.interactor.ObserveContinueReadingArtwork
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.profile.interactor.ObserveProfile
import tachiyomi.domain.tsuzuki.profile.model.ProfileReadingStats
import tachiyomi.domain.tsuzuki.profile.model.ProfileSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiProfileScreenModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading profile becomes guest then reflects authentication confirmation and logout`() = runTest(dispatcher) {
        val snapshot = MutableStateFlow(
            ProfileSnapshot(AccountState.LoggedOut, ProfileReadingStats(0, 0L, 0), emptyList()),
        )
        val items = MutableStateFlow(emptyList<HomeContinueReadingItem>())
        val profile = mockk<ObserveProfile> { every { subscribe() } returns snapshot }
        val reading = mockk<ObserveContinueReadingArtwork> { every { subscribe(5) } returns items }
        val model = TsuzukiProfileScreenModel(profile, reading)
        model.state.value.snapshot shouldBe null
        backgroundScope.launch { model.state.collect {} }
        runCurrent()
        model.state.value.snapshot shouldBe snapshot.value
        snapshot.value = snapshot.value.copy(
            account = AccountState.Authenticated(TsuzukiAccount("id", "reader@test.org")),
        )
        runCurrent()
        model.state.value.snapshot shouldBe snapshot.value
        snapshot.value = snapshot.value.copy(account = AccountState.EmailConfirmationRequired("reader@test.org"))
        runCurrent()
        model.state.value.snapshot shouldBe snapshot.value
        snapshot.value = snapshot.value.copy(account = AccountState.LoggedOut)
        runCurrent()
        model.state.value.snapshot?.account shouldBe AccountState.LoggedOut
        model.state.value.snapshot?.displayName shouldBe null
        model.state.value.snapshot?.avatarUrl shouldBe null
    }

    @Test
    fun `profile uses bounded real reading pipeline and reacts to empty and changed checkpoints`() = runTest(
        dispatcher,
    ) {
        val snapshot = MutableStateFlow(
            ProfileSnapshot(AccountState.LoggedOut, ProfileReadingStats(2, 7L, 3), emptyList()),
        )
        val items = MutableStateFlow(emptyList<HomeContinueReadingItem>())
        val profile = mockk<ObserveProfile> { every { subscribe() } returns snapshot }
        val reading = mockk<ObserveContinueReadingArtwork> { every { subscribe(5) } returns items }
        val model = TsuzukiProfileScreenModel(profile, reading)
        backgroundScope.launch { model.state.collect {} }
        runCurrent()
        model.state.value.continueReading shouldBe emptyList()
        val item = HomeContinueReadingItem("title", "Title", "chapter", "12a", 8L, 42L)
        items.value = listOf(item)
        runCurrent()
        model.state.value.continueReading shouldBe listOf(item)
        model.state.value.snapshot?.reading?.completedTitles shouldBe 3
        items.value = emptyList()
        runCurrent()
        model.state.value.continueReading shouldBe emptyList()
        verify(exactly = 1) { reading.subscribe(5) }
    }
}
