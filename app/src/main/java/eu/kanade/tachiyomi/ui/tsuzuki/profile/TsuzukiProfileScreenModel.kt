package eu.kanade.tachiyomi.ui.tsuzuki.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.tsuzuki.home.interactor.ObserveContinueReadingArtwork
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.profile.interactor.ObserveProfile
import tachiyomi.domain.tsuzuki.profile.model.ProfileSnapshot

@Immutable
data class TsuzukiProfileScreenState(
    val snapshot: ProfileSnapshot? = null,
    val continueReading: List<HomeContinueReadingItem> = emptyList(),
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiProfileScreenModel(
    observeProfile: ObserveProfile,
    observeContinueReadingArtwork: ObserveContinueReadingArtwork,
) : ViewModel() {
    val state = combine(
        observeProfile.subscribe(),
        observeContinueReadingArtwork.subscribe(limit = 5),
    ) { snapshot, continueReading ->
        TsuzukiProfileScreenState(snapshot, continueReading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TsuzukiProfileScreenState())
}
