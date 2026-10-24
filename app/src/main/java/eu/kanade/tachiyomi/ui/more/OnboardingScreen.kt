package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.tsuzuki.entry.TsuzukiEntryPage
import eu.kanade.presentation.tsuzuki.entry.TsuzukiEntryScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.entry.EntryChoice
import eu.kanade.tachiyomi.ui.tsuzuki.entry.EntryDestination
import eu.kanade.tachiyomi.ui.tsuzuki.entry.EntryFlowPolicy
import eu.kanade.tachiyomi.ui.tsuzuki.entry.TsuzukiLoginScreen
import eu.kanade.tachiyomi.ui.tsuzuki.entry.TsuzukiRegisterScreen
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState

class OnboardingScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val basePreferences = remember { context.appGraph.basePreferences }
        val shownOnboardingFlow by basePreferences.shownOnboardingFlow.collectAsState()

        BackHandler(enabled = !shownOnboardingFlow) {
            // The first-install choice must be explicit.
        }

        fun choose(choice: EntryChoice) {
            EntryFlowPolicy.choose(
                choice = choice,
                markComplete = { basePreferences.shownOnboardingFlow.set(true) },
                navigate = { destination ->
                    when (destination) {
                        EntryDestination.REGISTER -> navigator.replace(TsuzukiRegisterScreen)
                        EntryDestination.LOGIN -> navigator.replace(TsuzukiLoginScreen)
                        EntryDestination.HOME -> navigator.pop()
                    }
                },
            )
        }

        TsuzukiEntryScreen(
            page = TsuzukiEntryPage.WELCOME,
            onCreateAccount = { choose(EntryChoice.CREATE_ACCOUNT) },
            onSignIn = { choose(EntryChoice.SIGN_IN) },
            onGuest = { choose(EntryChoice.GUEST) },
        )
    }
}
