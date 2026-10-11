package eu.kanade.tachiyomi.ui.tsuzuki.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.home.TsuzukiHomeScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreen
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object TsuzukiHomeTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 0u,
            title = stringResource(MR.strings.tsuzuki_navigation_home),
            icon = painterResource(R.drawable.ic_tsuzuki_home_24dp),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiHomeScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        TsuzukiHomeScreen(
            state = state,
            onHeroRead = screenModel::readCatalogItem,
            onHeroDetails = screenModel::openCatalogItem,
            onContinueReading = { item ->
                context.startActivity(
                    ReaderActivity.newCanonicalIntent(
                        context = context,
                        canonicalChapterId = item.canonicalChapterId,
                    ),
                )
            },
            onFolder = { collectionId, folderId ->
                navigator.push(
                    HomeFolderScreen(
                        collectionId = collectionId,
                        folderId = folderId,
                    ),
                )
            },
        )

        LaunchedEffect(screenModel) {
            screenModel.events.collect { event ->
                when (event) {
                    is TsuzukiHomeEvent.OpenCanonicalTitle -> {
                        navigator.push(CanonicalTitleScreen(event.canonicalTitleId))
                    }
                    is TsuzukiHomeEvent.OpenReader -> {
                        context.startActivity(ReaderActivity.newCanonicalIntent(context, event.canonicalChapterId))
                    }
                    TsuzukiHomeEvent.ReadingUnavailable -> context.toast(MR.strings.tsuzuki_home_reading_unavailable)
                }
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
