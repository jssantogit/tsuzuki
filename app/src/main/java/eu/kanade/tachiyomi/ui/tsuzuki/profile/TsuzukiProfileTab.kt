package eu.kanade.tachiyomi.ui.tsuzuki.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiAccountScreen
import eu.kanade.presentation.tsuzuki.profile.TsuzukiProfileScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Person
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object TsuzukiProfileTab : Tab {
    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 3u,
            title = stringResource(MR.strings.tsuzuki_navigation_profile),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Person),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiProfileScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        TsuzukiProfileScreen(
            snapshot = state.snapshot,
            continueReading = state.continueReading,
            onSettings = { navigator.push(SettingsScreen()) },
            onAccount = { navigator.push(SettingsTsuzukiAccountScreen) },
            onTracking = { navigator.push(SettingsTrackingScreen) },
            onContinueReading = { item ->
                context.startActivity(ReaderActivity.newCanonicalIntent(context, item.canonicalChapterId))
            },
        )
    }
}
